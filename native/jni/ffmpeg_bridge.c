// JNI bridge between LibreAmp's Kotlin playback engine and a statically-linked,
// LGPL-only, audio-decode-only FFmpeg build. FFmpeg does all demuxing/decoding;
// this file never touches Android's MediaCodec/MediaExtractor/MediaStore.
//
// Threading contract: the Kotlin side must serialize ALL native calls for a given
// handle (open/read/seek/close) onto a single decode thread. No locking is done
// here on purpose (see project plan, "Threading" section).

#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavformat/avio.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>

#include "ffmpeg_bridge.h"

#define TAG "LibreAmpNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define AVIO_BUFFER_SIZE 32768
#define OUT_SAMPLE_FMT AV_SAMPLE_FMT_S16
#define OUT_CHANNELS 2

typedef struct {
    int fd;
    int64_t size;     // cached st_size at open time; -1 if unknown/non-regular
    int64_t position;
} IoOpaque;

typedef struct {
    IoOpaque io;
    uint8_t *avio_buffer;
    AVIOContext *avio_ctx;
    AVFormatContext *fmt_ctx;

    int audio_stream_index;
    AVCodecContext *codec_ctx;
    SwrContext *swr_ctx;

    AVPacket *pkt;
    AVFrame *frame;

    // Optional libavfilter effects chain (equalizer, crossfeed, ...) inserted
    // between the decoder and swresample. The graph is constrained to emit the
    // decoder's own sample format/rate/layout so the swr context stays valid
    // whether or not a graph is active.
    AVFilterGraph *filter_graph;
    AVFilterContext *buffersrc_ctx;
    AVFilterContext *buffersink_ctx;
    AVFrame *filt_frame;
    AVChannelLayout graph_layout;
    char *filter_desc;       // owned copy, so the graph can be rebuilt after a seek

    int target_sample_rate;
    int64_t duration_us;

    // Scratch buffer holding already-converted-but-not-yet-delivered PCM bytes,
    // because swr_convert output sizes rarely line up with the caller's chunk size.
    uint8_t *scratch;
    size_t scratch_capacity;
    size_t scratch_len;   // valid bytes starting at offset 0
    size_t scratch_pos;   // consumed offset

    int eof_from_demuxer;    // av_read_frame returned AVERROR_EOF
    int decoder_flushed;     // avcodec_send_packet(NULL) issued
    int fully_drained;       // no more frames will ever come out
} PlayerContext;

// ---------------------------------------------------------------------------
// Custom AVIO read/seek callbacks, backed by pread/lseek on a raw fd handed to
// us from Kotlin's ContentResolver.openFileDescriptor(uri, "r").detachFd().
// ---------------------------------------------------------------------------

static int read_packet_cb(void *opaque, uint8_t *buf, int buf_size) {
    IoOpaque *io = (IoOpaque *) opaque;
    ssize_t n = pread(io->fd, buf, (size_t) buf_size, (off_t) io->position);
    if (n < 0) {
        LOGE("read_packet_cb: pread failed errno=%d", errno);
        return AVERROR(EIO);
    }
    if (n == 0) {
        return AVERROR_EOF;
    }
    io->position += n;
    return (int) n;
}

static int64_t seek_cb(void *opaque, int64_t offset, int whence) {
    IoOpaque *io = (IoOpaque *) opaque;
    int force = whence & AVSEEK_FORCE;
    whence &= ~AVSEEK_FORCE;
    (void) force;

    if (whence == AVSEEK_SIZE) {
        return io->size >= 0 ? io->size : AVERROR(ENOSYS);
    }

    int64_t new_pos;
    switch (whence) {
        case SEEK_SET:
            new_pos = offset;
            break;
        case SEEK_CUR:
            new_pos = io->position + offset;
            break;
        case SEEK_END:
            if (io->size < 0) return AVERROR(ENOSYS);
            new_pos = io->size + offset;
            break;
        default:
            return AVERROR(EINVAL);
    }
    if (new_pos < 0) return AVERROR(EINVAL);
    io->position = new_pos;
    return new_pos;
}

// ---------------------------------------------------------------------------
// Scratch buffer helpers
// ---------------------------------------------------------------------------

static void scratch_compact(PlayerContext *ctx) {
    if (ctx->scratch_pos == 0) return;
    size_t remaining = ctx->scratch_len - ctx->scratch_pos;
    if (remaining > 0) {
        memmove(ctx->scratch, ctx->scratch + ctx->scratch_pos, remaining);
    }
    ctx->scratch_len = remaining;
    ctx->scratch_pos = 0;
}

static int scratch_append(PlayerContext *ctx, const uint8_t *data, size_t len) {
    scratch_compact(ctx);
    size_t needed = ctx->scratch_len + len;
    if (needed > ctx->scratch_capacity) {
        size_t new_cap = ctx->scratch_capacity == 0 ? 65536 : ctx->scratch_capacity;
        while (new_cap < needed) new_cap *= 2;
        uint8_t *grown = (uint8_t *) av_realloc(ctx->scratch, new_cap);
        if (!grown) return -1;
        ctx->scratch = grown;
        ctx->scratch_capacity = new_cap;
    }
    memcpy(ctx->scratch + ctx->scratch_len, data, len);
    ctx->scratch_len += len;
    return 0;
}

// Decode+resample one AVFrame and append the resulting PCM to the scratch buffer.
static int convert_and_append(PlayerContext *ctx, AVFrame *frame) {
    int out_samples = (int) av_rescale_rnd(
            swr_get_delay(ctx->swr_ctx, ctx->codec_ctx->sample_rate) + frame->nb_samples,
            ctx->target_sample_rate, ctx->codec_ctx->sample_rate, AV_ROUND_UP);
    int out_buf_size = av_samples_get_buffer_size(
            NULL, OUT_CHANNELS, out_samples, OUT_SAMPLE_FMT, 1);
    if (out_buf_size <= 0) return 0;

    uint8_t *out_buf = (uint8_t *) av_malloc((size_t) out_buf_size);
    if (!out_buf) return -1;

    uint8_t *out_planes[1] = {out_buf};
    int converted = swr_convert(ctx->swr_ctx, out_planes, out_samples,
                                 (const uint8_t **) frame->extended_data, frame->nb_samples);
    if (converted < 0) {
        av_free(out_buf);
        return -1;
    }
    int converted_bytes = av_samples_get_buffer_size(
            NULL, OUT_CHANNELS, converted, OUT_SAMPLE_FMT, 1);
    int rc = 0;
    if (converted_bytes > 0) {
        rc = scratch_append(ctx, out_buf, (size_t) converted_bytes);
    }
    av_free(out_buf);
    return rc;
}

// ---------------------------------------------------------------------------
// libavfilter effects chain
// ---------------------------------------------------------------------------

static void free_filter_graph(PlayerContext *ctx) {
    if (ctx->filter_graph) avfilter_graph_free(&ctx->filter_graph);
    ctx->buffersrc_ctx = NULL;
    ctx->buffersink_ctx = NULL;
}

/**
 * Builds abuffer -> [filter_desc] -> abuffersink, pinned to the decoder's own
 * output format so downstream swresample needs no reconfiguration. Any format
 * conversion a filter needs is auto-negotiated by avfilter internally.
 * Returns 0 on success; on failure the graph is torn down (unfiltered passthrough).
 */
static int build_filter_graph(PlayerContext *ctx, const char *filter_desc) {
    free_filter_graph(ctx);
    if (!filter_desc || !*filter_desc) return 0; // no effects: passthrough

    const AVFilter *abuffersrc = avfilter_get_by_name("abuffer");
    const AVFilter *abuffersink = avfilter_get_by_name("abuffersink");
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs = avfilter_inout_alloc();
    ctx->filter_graph = avfilter_graph_alloc();
    int rc = -1;

    if (!abuffersrc || !abuffersink || !outputs || !inputs || !ctx->filter_graph) {
        LOGE("build_filter_graph: allocation failed");
        goto done;
    }

    char layout_str[64];
    av_channel_layout_describe(&ctx->graph_layout, layout_str, sizeof(layout_str));

    char src_args[256];
    snprintf(src_args, sizeof(src_args),
             "time_base=1/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
             ctx->codec_ctx->sample_rate, ctx->codec_ctx->sample_rate,
             av_get_sample_fmt_name(ctx->codec_ctx->sample_fmt), layout_str);

    if ((rc = avfilter_graph_create_filter(&ctx->buffersrc_ctx, abuffersrc, "in",
                                           src_args, NULL, ctx->filter_graph)) < 0) {
        LOGE("build_filter_graph: abuffer create failed: %d", rc);
        goto done;
    }
    if ((rc = avfilter_graph_create_filter(&ctx->buffersink_ctx, abuffersink, "out",
                                           NULL, NULL, ctx->filter_graph)) < 0) {
        LOGE("build_filter_graph: abuffersink create failed: %d", rc);
        goto done;
    }

    // Pin the sink to the decoder's format so convert_and_append/swr stay valid.
    const enum AVSampleFormat sink_fmts[] = {ctx->codec_ctx->sample_fmt, AV_SAMPLE_FMT_NONE};
    const int sink_rates[] = {ctx->codec_ctx->sample_rate, -1};
    if ((rc = av_opt_set_int_list(ctx->buffersink_ctx, "sample_fmts", sink_fmts,
                                  AV_SAMPLE_FMT_NONE, AV_OPT_SEARCH_CHILDREN)) < 0 ||
        (rc = av_opt_set_int_list(ctx->buffersink_ctx, "sample_rates", sink_rates,
                                  -1, AV_OPT_SEARCH_CHILDREN)) < 0 ||
        (rc = av_opt_set(ctx->buffersink_ctx, "ch_layouts", layout_str,
                         AV_OPT_SEARCH_CHILDREN)) < 0) {
        LOGE("build_filter_graph: sink format pinning failed: %d", rc);
        goto done;
    }

    // Naming is from the parsed sub-graph's perspective: its unlinked input is
    // fed by our buffersrc, its unlinked output feeds our buffersink.
    outputs->name = av_strdup("in");
    outputs->filter_ctx = ctx->buffersrc_ctx;
    outputs->pad_idx = 0;
    outputs->next = NULL;
    inputs->name = av_strdup("out");
    inputs->filter_ctx = ctx->buffersink_ctx;
    inputs->pad_idx = 0;
    inputs->next = NULL;
    if (!outputs->name || !inputs->name) {
        rc = AVERROR(ENOMEM);
        goto done;
    }

    if ((rc = avfilter_graph_parse_ptr(ctx->filter_graph, filter_desc,
                                       &inputs, &outputs, NULL)) < 0) {
        LOGE("build_filter_graph: parse failed for '%s': %d", filter_desc, rc);
        goto done;
    }
    if ((rc = avfilter_graph_config(ctx->filter_graph, NULL)) < 0) {
        LOGE("build_filter_graph: config failed: %d", rc);
        goto done;
    }
    rc = 0;
    LOGI("filter graph active: %s", filter_desc);

done:
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);
    if (rc < 0) free_filter_graph(ctx);
    return rc;
}

// Push one decoded frame through the graph and append every frame it yields.
static int filter_and_append(PlayerContext *ctx, AVFrame *frame) {
    int rc = av_buffersrc_add_frame_flags(ctx->buffersrc_ctx, frame,
                                          AV_BUFFERSRC_FLAG_KEEP_REF);
    if (rc < 0) {
        LOGW("av_buffersrc_add_frame failed: %d", rc);
        return -1;
    }
    while (1) {
        rc = av_buffersink_get_frame(ctx->buffersink_ctx, ctx->filt_frame);
        if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) return 0;
        if (rc < 0) {
            LOGW("av_buffersink_get_frame failed: %d", rc);
            return -1;
        }
        int append_rc = convert_and_append(ctx, ctx->filt_frame);
        av_frame_unref(ctx->filt_frame);
        if (append_rc < 0) return -1;
    }
}

// Pull any frames the decoder already has buffered (call after send_packet).
static int drain_decoder(PlayerContext *ctx) {
    int ret;
    while ((ret = avcodec_receive_frame(ctx->codec_ctx, ctx->frame)) == 0) {
        int rc = ctx->filter_graph ? filter_and_append(ctx, ctx->frame)
                                   : convert_and_append(ctx, ctx->frame);
        av_frame_unref(ctx->frame);
        if (rc < 0) return -1;
    }
    if (ret == AVERROR_EOF) {
        // Flush the graph so its internal buffering (e.g. firequalizer's FIR
        // delay) is emitted rather than truncated at end-of-track.
        if (ctx->filter_graph) filter_and_append(ctx, NULL);
        ctx->fully_drained = 1;
    } else if (ret != AVERROR(EAGAIN)) {
        LOGW("avcodec_receive_frame error: %d", ret);
    }
    return 0;
}

// Demux+decode more audio until at least `want_bytes` are available in scratch,
// or the stream is fully drained.
static void fill_scratch(PlayerContext *ctx, size_t want_bytes) {
    while ((ctx->scratch_len - ctx->scratch_pos) < want_bytes && !ctx->fully_drained) {
        if (ctx->eof_from_demuxer) {
            if (!ctx->decoder_flushed) {
                avcodec_send_packet(ctx->codec_ctx, NULL); // flush
                ctx->decoder_flushed = 1;
            }
            if (drain_decoder(ctx) < 0) return;
            if (!ctx->fully_drained && ctx->decoder_flushed) {
                // Nothing left to send and receive returned EAGAIN forever - bail.
                ctx->fully_drained = 1;
            }
            continue;
        }

        av_packet_unref(ctx->pkt);
        int rc = av_read_frame(ctx->fmt_ctx, ctx->pkt);
        if (rc == AVERROR_EOF) {
            ctx->eof_from_demuxer = 1;
            continue;
        } else if (rc < 0) {
            LOGE("av_read_frame error: %d", rc);
            ctx->eof_from_demuxer = 1;
            continue;
        }

        if (ctx->pkt->stream_index != ctx->audio_stream_index) {
            av_packet_unref(ctx->pkt);
            continue;
        }

        rc = avcodec_send_packet(ctx->codec_ctx, ctx->pkt);
        av_packet_unref(ctx->pkt);
        if (rc < 0 && rc != AVERROR(EAGAIN)) {
            LOGW("avcodec_send_packet error: %d", rc);
            continue;
        }
        if (drain_decoder(ctx) < 0) return;
    }
}

static void free_player_context(PlayerContext *ctx) {
    if (!ctx) return;
    free_filter_graph(ctx);
    if (ctx->filter_desc) av_freep(&ctx->filter_desc);
    av_channel_layout_uninit(&ctx->graph_layout);
    if (ctx->swr_ctx) swr_free(&ctx->swr_ctx);
    if (ctx->codec_ctx) avcodec_free_context(&ctx->codec_ctx);
    if (ctx->pkt) av_packet_free(&ctx->pkt);
    if (ctx->frame) av_frame_free(&ctx->frame);
    if (ctx->filt_frame) av_frame_free(&ctx->filt_frame);
    if (ctx->fmt_ctx) {
        // AVFMT_FLAG_CUSTOM_IO means avformat_close_input will NOT free avio_ctx.
        avformat_close_input(&ctx->fmt_ctx);
    }
    if (ctx->avio_ctx) {
        // avio internals (e.g. ffio_ensure_seekback, used when reading large
        // ID3v2 tags such as embedded cover art) may have reallocated the
        // buffer via av_realloc since avio_alloc_context() was called, so the
        // pointer to free is whatever avio_ctx->buffer currently holds, not
        // the original ctx->avio_buffer pointer - that one may already be
        // freed, and freeing it again would be a double free.
        av_freep(&ctx->avio_ctx->buffer);
        avio_context_free(&ctx->avio_ctx);
    } else if (ctx->avio_buffer) {
        // avio_alloc_context() was never reached (or failed) - free directly.
        av_freep(&ctx->avio_buffer);
    }
    if (ctx->scratch) av_freep(&ctx->scratch);
    if (ctx->io.fd >= 0) close(ctx->io.fd);
    free(ctx);
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeOpen(
        JNIEnv *env, jobject thiz, jint fd, jstring displayName, jint targetSampleRate) {
    (void) thiz;

    PlayerContext *ctx = (PlayerContext *) calloc(1, sizeof(PlayerContext));
    if (!ctx) return 0;
    ctx->io.fd = fd;
    ctx->io.position = 0;
    ctx->audio_stream_index = -1;
    ctx->target_sample_rate = targetSampleRate > 0 ? targetSampleRate : 44100;

    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode)) {
        ctx->io.size = st.st_size;
    } else {
        ctx->io.size = -1; // non-seekable / unknown: AVSEEK_SIZE and SEEK_END will fail gracefully
        LOGW("nativeOpen: fd is not a regular file, seeking may be unavailable");
    }

    ctx->avio_buffer = (uint8_t *) av_malloc(AVIO_BUFFER_SIZE);
    if (!ctx->avio_buffer) {
        free_player_context(ctx);
        return 0;
    }
    ctx->avio_ctx = avio_alloc_context(ctx->avio_buffer, AVIO_BUFFER_SIZE, 0,
                                        &ctx->io, read_packet_cb, NULL, seek_cb);
    if (!ctx->avio_ctx) {
        free_player_context(ctx);
        return 0;
    }

    ctx->fmt_ctx = avformat_alloc_context();
    if (!ctx->fmt_ctx) {
        free_player_context(ctx);
        return 0;
    }
    ctx->fmt_ctx->pb = ctx->avio_ctx;
    ctx->fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

    const char *filename_hint = NULL;
    if (displayName) {
        filename_hint = (*env)->GetStringUTFChars(env, displayName, NULL);
    }

    int rc = avformat_open_input(&ctx->fmt_ctx, filename_hint, NULL, NULL);

    if (filename_hint) {
        (*env)->ReleaseStringUTFChars(env, displayName, filename_hint);
    }

    if (rc < 0) {
        LOGE("avformat_open_input failed: %d", rc);
        // avformat_open_input frees fmt_ctx itself on failure.
        ctx->fmt_ctx = NULL;
        free_player_context(ctx);
        return 0;
    }

    if (avformat_find_stream_info(ctx->fmt_ctx, NULL) < 0) {
        LOGE("avformat_find_stream_info failed");
        free_player_context(ctx);
        return 0;
    }

    int stream_idx = av_find_best_stream(ctx->fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream_idx < 0) {
        LOGE("no audio stream found");
        free_player_context(ctx);
        return 0;
    }
    ctx->audio_stream_index = stream_idx;

    AVCodecParameters *par = ctx->fmt_ctx->streams[stream_idx]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(par->codec_id);
    if (!decoder) {
        LOGE("no decoder for codec id %d", par->codec_id);
        free_player_context(ctx);
        return 0;
    }
    ctx->codec_ctx = avcodec_alloc_context3(decoder);
    if (!ctx->codec_ctx || avcodec_parameters_to_context(ctx->codec_ctx, par) < 0) {
        free_player_context(ctx);
        return 0;
    }
    if (avcodec_open2(ctx->codec_ctx, decoder, NULL) < 0) {
        LOGE("avcodec_open2 failed");
        free_player_context(ctx);
        return 0;
    }

    AVChannelLayout in_layout;
    if (ctx->codec_ctx->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC ||
        ctx->codec_ctx->ch_layout.nb_channels == 0) {
        av_channel_layout_default(&in_layout, ctx->codec_ctx->ch_layout.nb_channels > 0
                                                       ? ctx->codec_ctx->ch_layout.nb_channels : 2);
    } else {
        av_channel_layout_copy(&in_layout, &ctx->codec_ctx->ch_layout);
    }
    AVChannelLayout out_layout = AV_CHANNEL_LAYOUT_STEREO;

    int swr_rc = swr_alloc_set_opts2(&ctx->swr_ctx,
                                      &out_layout, OUT_SAMPLE_FMT, ctx->target_sample_rate,
                                      &in_layout, ctx->codec_ctx->sample_fmt, ctx->codec_ctx->sample_rate,
                                      0, NULL);
    // Kept for the filter graph, which must emit exactly what swr expects here.
    av_channel_layout_copy(&ctx->graph_layout, &in_layout);
    av_channel_layout_uninit(&in_layout);
    if (swr_rc < 0 || !ctx->swr_ctx || swr_init(ctx->swr_ctx) < 0) {
        LOGE("swresample init failed");
        free_player_context(ctx);
        return 0;
    }

    ctx->pkt = av_packet_alloc();
    ctx->frame = av_frame_alloc();
    ctx->filt_frame = av_frame_alloc();
    if (!ctx->pkt || !ctx->frame || !ctx->filt_frame) {
        free_player_context(ctx);
        return 0;
    }

    if (ctx->fmt_ctx->duration != AV_NOPTS_VALUE) {
        ctx->duration_us = ctx->fmt_ctx->duration; // AV_TIME_BASE is microseconds
    } else {
        AVStream *st_audio = ctx->fmt_ctx->streams[stream_idx];
        if (st_audio->duration != AV_NOPTS_VALUE) {
            ctx->duration_us = (int64_t) (st_audio->duration * av_q2d(st_audio->time_base) * 1000000.0);
        } else {
            ctx->duration_us = -1;
        }
    }

    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jlong JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetDurationUs(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    if (!ctx) return -1;
    return (jlong) ctx->duration_us;
}

JNIEXPORT jint JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeReadPcmChunk(
        JNIEnv *env, jobject thiz, jlong handle, jobject byteBuffer, jint capacity) {
    (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    if (!ctx) return -2;

    uint8_t *out = (uint8_t *) (*env)->GetDirectBufferAddress(env, byteBuffer);
    if (!out) {
        LOGE("nativeReadPcmChunk: not a direct ByteBuffer");
        return -2;
    }

    fill_scratch(ctx, (size_t) capacity);

    size_t available = ctx->scratch_len - ctx->scratch_pos;
    if (available == 0 && ctx->fully_drained) {
        return -1; // true EOF
    }
    size_t to_copy = available < (size_t) capacity ? available : (size_t) capacity;
    if (to_copy > 0) {
        memcpy(out, ctx->scratch + ctx->scratch_pos, to_copy);
        ctx->scratch_pos += to_copy;
    }
    return (jint) to_copy;
}

JNIEXPORT jboolean JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeSeekUs(
        JNIEnv *env, jobject thiz, jlong handle, jlong positionUs) {
    (void) env; (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    if (!ctx) return JNI_FALSE;

    if (ctx->io.size < 0) {
        LOGW("nativeSeekUs: underlying fd is not seekable");
        return JNI_FALSE;
    }

    int rc = avformat_seek_file(ctx->fmt_ctx, -1, INT64_MIN, positionUs, INT64_MAX, 0);
    if (rc < 0) {
        LOGE("avformat_seek_file failed: %d", rc);
        return JNI_FALSE;
    }
    avcodec_flush_buffers(ctx->codec_ctx);
    // Rebuild rather than reuse: the graph may already have been flushed to EOF
    // at end-of-track (which would reject further input), and rebuilding also
    // drops stale filter tails (echo, FIR delay) from the pre-seek position.
    if (ctx->filter_desc) build_filter_graph(ctx, ctx->filter_desc);
    ctx->scratch_len = 0;
    ctx->scratch_pos = 0;
    ctx->eof_from_demuxer = 0;
    ctx->decoder_flushed = 0;
    ctx->fully_drained = 0;
    return JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetTags(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!ctx || !ctx->fmt_ctx) {
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    // Count entries first (format-level + the audio stream's own metadata).
    int count = 0;
    AVDictionaryEntry *e = NULL;
    while ((e = av_dict_get(ctx->fmt_ctx->metadata, "", e, AV_DICT_IGNORE_SUFFIX))) count++;
    AVStream *st_audio = ctx->fmt_ctx->streams[ctx->audio_stream_index];
    e = NULL;
    while ((e = av_dict_get(st_audio->metadata, "", e, AV_DICT_IGNORE_SUFFIX))) count++;

    jobjectArray result = (*env)->NewObjectArray(env, count * 2, stringClass, NULL);
    int idx = 0;
    e = NULL;
    while ((e = av_dict_get(ctx->fmt_ctx->metadata, "", e, AV_DICT_IGNORE_SUFFIX))) {
        (*env)->SetObjectArrayElement(env, result, idx++, (*env)->NewStringUTF(env, e->key));
        (*env)->SetObjectArrayElement(env, result, idx++, (*env)->NewStringUTF(env, e->value));
    }
    e = NULL;
    while ((e = av_dict_get(st_audio->metadata, "", e, AV_DICT_IGNORE_SUFFIX))) {
        (*env)->SetObjectArrayElement(env, result, idx++, (*env)->NewStringUTF(env, e->key));
        (*env)->SetObjectArrayElement(env, result, idx++, (*env)->NewStringUTF(env, e->value));
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetEmbeddedArt(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    if (!ctx || !ctx->fmt_ctx) return NULL;

    for (unsigned i = 0; i < ctx->fmt_ctx->nb_streams; i++) {
        AVStream *s = ctx->fmt_ctx->streams[i];
        if (s->disposition & AV_DISPOSITION_ATTACHED_PIC) {
            AVPacket *pic = &s->attached_pic;
            if (pic->size <= 0) continue;
            jbyteArray arr = (*env)->NewByteArray(env, pic->size);
            if (!arr) return NULL;
            (*env)->SetByteArrayRegion(env, arr, 0, pic->size, (const jbyte *) pic->data);
            return arr;
        }
    }
    return NULL;
}

JNIEXPORT void JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeClose(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    free_player_context(ctx);
}

/**
 * Installs (or clears, when [filterDesc] is null/empty) an ffmpeg filter-graph
 * string such as "superequalizer=1b=6:2b=4" or "bass=g=5,crossfeed=strength=0.4".
 * Like every other native call, this MUST run on the decode thread.
 * Returns JNI_TRUE if the requested chain is now active.
 */
JNIEXPORT jboolean JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeSetFilterGraph(
        JNIEnv *env, jobject thiz, jlong handle, jstring filterDesc) {
    (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    if (!ctx || !ctx->codec_ctx) return JNI_FALSE;

    const char *desc = filterDesc ? (*env)->GetStringUTFChars(env, filterDesc, NULL) : NULL;
    int rc = build_filter_graph(ctx, desc);
    if (ctx->filter_desc) av_freep(&ctx->filter_desc);
    if (rc == 0 && desc && *desc) ctx->filter_desc = av_strdup(desc);
    if (desc) (*env)->ReleaseStringUTFChars(env, filterDesc, desc);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * Adjusts a parameter on an already-running filter without rebuilding the graph
 * (e.g. target "equalizer", cmd "gain", arg "5"), for click-free live EQ edits.
 * Returns JNI_TRUE if the filter accepted the command.
 */
JNIEXPORT jboolean JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeSendFilterCommand(
        JNIEnv *env, jobject thiz, jlong handle, jstring target, jstring cmd, jstring arg) {
    (void) thiz;
    PlayerContext *ctx = (PlayerContext *) (intptr_t) handle;
    if (!ctx || !ctx->filter_graph || !target || !cmd) return JNI_FALSE;

    const char *t = (*env)->GetStringUTFChars(env, target, NULL);
    const char *c = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char *a = arg ? (*env)->GetStringUTFChars(env, arg, NULL) : "";
    char response[256];
    int rc = avfilter_graph_send_command(ctx->filter_graph, t, c, a,
                                          response, sizeof(response), 0);
    if (rc < 0) LOGW("send_command %s.%s=%s failed: %d (%s)", t, c, a, rc, response);
    (*env)->ReleaseStringUTFChars(env, target, t);
    (*env)->ReleaseStringUTFChars(env, cmd, c);
    if (arg) (*env)->ReleaseStringUTFChars(env, arg, a);
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_libreamp_player_native_1bridge_NativeBridge_nativeGetFfmpegConfig(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, avcodec_configuration());
}
