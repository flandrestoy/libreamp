package dev.libreamp.player.ui.filepicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import dev.libreamp.player.R
import dev.libreamp.player.databinding.ActivityFilePickerBinding
import dev.libreamp.player.util.MediaFileFilter
import java.io.File

/**
 * Browses the real filesystem (no SAF) so playlist entries carry plain absolute
 * paths. Selection persists across directory navigation; checking a directory
 * pulls in its direct filtered children only (no recursive walk).
 */
class FilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilePickerBinding
    private lateinit var adapter: FileBrowserAdapter

    private var currentDir: File = Environment.getExternalStorageDirectory()
    private val selectedPaths = LinkedHashSet<String>()

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissionAndProceed() }

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) navigateTo(currentDir) else denyAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = FileBrowserAdapter(
            onNavigate = { entry -> navigateTo(entry.target) },
            onToggle = { entry, checked -> onEntryToggled(entry, checked) },
            isChecked = { entry -> isEntryChecked(entry) }
        )
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = adapter

        binding.editAddress.setText(currentDir.absolutePath)
        binding.editAddress.setOnEditorActionListener { _, _, _ -> navigateToTypedPath(); true }
        binding.buttonGo.setOnClickListener { navigateToTypedPath() }

        binding.buttonSelectAll.setOnClickListener { selectAllFilesInCurrentDir() }
        binding.buttonAbort.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.buttonConfirm.setOnClickListener {
            val result = Intent().putStringArrayListExtra(EXTRA_SELECTED_PATHS, ArrayList(selectedPaths))
            setResult(RESULT_OK, result)
            finish()
        }

        checkPermissionAndProceed()
    }

    private fun checkPermissionAndProceed() {
        if (hasStorageAccess()) {
            navigateTo(currentDir)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                manageStorageLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun denyAndFinish() {
        Toast.makeText(this, R.string.msg_storage_permission_required, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun navigateToTypedPath() {
        val path = binding.editAddress.text.toString().trim()
        val target = File(path)
        if (target.isDirectory) {
            navigateTo(target)
        } else {
            Toast.makeText(this, R.string.msg_cannot_read_dir, Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateTo(dir: File) {
        val children = dir.listFiles()
        if (children == null) {
            Toast.makeText(this, R.string.msg_cannot_read_dir, Toast.LENGTH_SHORT).show()
            return
        }
        currentDir = dir
        binding.editAddress.setText(dir.absolutePath)

        val directories = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = children.filter { it.isFile && MediaFileFilter.matches(it.name) }
            .sortedBy { it.name.lowercase() }

        val entries = mutableListOf<FsEntry>()
        dir.parentFile?.let { parent -> entries.add(FsEntry("..", parent, FsEntryKind.PARENT)) }
        directories.forEach { entries.add(FsEntry(it.name, it, FsEntryKind.DIRECTORY)) }
        files.forEach { entries.add(FsEntry(it.name, it, FsEntryKind.FILE)) }

        adapter.submitList(entries)
        updateSelectionCount()
    }

    private fun filteredFilesIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && MediaFileFilter.matches(f.name) }?.toList() ?: emptyList()

    private fun isEntryChecked(entry: FsEntry): Boolean = when (entry.kind) {
        FsEntryKind.PARENT -> false
        FsEntryKind.FILE -> entry.target.absolutePath in selectedPaths
        FsEntryKind.DIRECTORY -> {
            val children = filteredFilesIn(entry.target)
            children.isNotEmpty() && children.all { it.absolutePath in selectedPaths }
        }
    }

    private fun onEntryToggled(entry: FsEntry, checked: Boolean) {
        when (entry.kind) {
            FsEntryKind.FILE -> {
                if (checked) selectedPaths.add(entry.target.absolutePath)
                else selectedPaths.remove(entry.target.absolutePath)
            }
            FsEntryKind.DIRECTORY -> {
                val paths = filteredFilesIn(entry.target).map { it.absolutePath }
                if (checked) selectedPaths.addAll(paths) else selectedPaths.removeAll(paths.toSet())
            }
            FsEntryKind.PARENT -> Unit
        }
        adapter.refreshCheckboxes()
        updateSelectionCount()
    }

    private fun selectAllFilesInCurrentDir() {
        val paths = filteredFilesIn(currentDir).map { it.absolutePath }
        selectedPaths.addAll(paths)
        adapter.refreshCheckboxes()
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        binding.textSelectionCount.text = getString(R.string.files_selected_count, selectedPaths.size)
    }

    companion object {
        const val EXTRA_SELECTED_PATHS = "selected_paths"
    }
}
