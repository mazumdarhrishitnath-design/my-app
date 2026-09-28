package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ApkExportUtil {

    const val APK_DISPLAY_NAME = "CHAT_100m_Nearby.apk"
    const val ZIP_DISPLAY_NAME = "CHAT_100m_Project_Bundle.zip"
    const val BUILD_PATH = "app/build/outputs/apk/debug/app-debug.apk"

    /**
     * Copies the installed application base APK to app's accessible cache directory
     * and shares it via system Intent.ACTION_SEND with FileProvider.
     */
    fun shareInstalledApk(context: Context) {
        try {
            val sourceApkPath = context.applicationInfo.sourceDir
            val sourceFile = File(sourceApkPath)

            if (!sourceFile.exists()) {
                Toast.makeText(context, "APK file not found on system", Toast.LENGTH_SHORT).show()
                return
            }

            val apkDir = File(context.cacheDir, "apks").apply { mkdirs() }
            val destFile = File(apkDir, APK_DISPLAY_NAME)

            // Copy APK to provider path
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Share via FileProvider
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                putExtra(Intent.EXTRA_SUBJECT, "CHAT 100m Offline APK")
                putExtra(Intent.EXTRA_TEXT, "Download & Install CHAT 100m Offline Nearby Chat APK (100m range):")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Export APK: Choose destination").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing APK: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Creates a complete Project ZIP bundle containing:
     * - CHAT_100m_Nearby.apk
     * - README_INSTALL.txt
     * - ARCHITECTURE_INFO.txt
     * and shares or saves it via system chooser.
     */
    fun exportProjectZip(context: Context) {
        try {
            val sourceApkPath = context.applicationInfo.sourceDir
            val sourceFile = File(sourceApkPath)

            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportsDir, ZIP_DISPLAY_NAME)

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 1. Add APK into ZIP
                if (sourceFile.exists()) {
                    zos.putNextEntry(ZipEntry(APK_DISPLAY_NAME))
                    FileInputStream(sourceFile).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                }

                // 2. Add Readme & Installation guide
                val readmeContent = """
                    ========================================
                    CHAT 100m WITHOUT NETWORK - PROJECT BUNDLE
                    ========================================
                    
                    Features:
                    - 100m Physical Range Offline Mesh / P2P Chat
                    - No Internet, WiFi Router, or Mobile Data Required
                    - Uses Google Nearby Connections & Bluetooth / WiFi-Direct
                    - Real-time Radar with Live Distance & RSSI Signal Strength
                    - Local Room Database message persistence
                    
                    Installation Instructions:
                    1. Copy '${APK_DISPLAY_NAME}' to your Android phone.
                    2. Open the file and allow 'Install from unknown sources' if prompted.
                    3. Launch CHAT 100m, enter your Username and 2-digit tag (e.g. rahul#44).
                    4. Grant Bluetooth and Location permissions.
                    5. Keep both phones within 100 meters to chat offline!
                    
                    Build Artifact Path in AI Studio:
                    app/build/outputs/apk/debug/app-debug.apk
                    ========================================
                """.trimIndent()

                zos.putNextEntry(ZipEntry("README_INSTALL.txt"))
                zos.write(readmeContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 3. Add Project Info
                val projectInfo = """
                    Project Name: CHAT 100m WITHOUT NETWORK
                    Package ID: ${context.packageName}
                    Export Type: Full Project & APK Bundle
                    Export Timestamp: ${System.currentTimeMillis()}
                    Database: Room Local SQLite Persistence
                    Protocol: Google Nearby Connections (P2P_CLUSTER)
                """.trimIndent()

                zos.putNextEntry(ZipEntry("PROJECT_INFO.txt"))
                zos.write(projectInfo.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // Share ZIP via FileProvider
            val zipUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, zipUri)
                putExtra(Intent.EXTRA_SUBJECT, "CHAT 100m - Complete Project & APK ZIP")
                putExtra(Intent.EXTRA_TEXT, "Download the complete CHAT 100m Project ZIP including APK and installation guide:")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Download / Export Project ZIP via:").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Error creating Project ZIP: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Attempts to copy the APK to the public Downloads folder using MediaStore on API 29+
     * or standard filesystem on earlier Android versions.
     */
    fun saveApkToDownloads(context: Context): Boolean {
        return try {
            val sourceApkPath = context.applicationInfo.sourceDir
            val sourceFile = File(sourceApkPath)
            if (!sourceFile.exists()) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, APK_DISPLAY_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return false

                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(output)
                    }
                }
                true
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null) {
                    downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, APK_DISPLAY_NAME)
                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Saves the project ZIP directly to the public Downloads folder.
     */
    fun saveZipToDownloads(context: Context): Boolean {
        return try {
            val sourceApkPath = context.applicationInfo.sourceDir
            val sourceFile = File(sourceApkPath)
            if (!sourceFile.exists()) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, ZIP_DISPLAY_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return false

                resolver.openOutputStream(uri)?.use { output ->
                    ZipOutputStream(output).use { zos ->
                        zos.putNextEntry(ZipEntry(APK_DISPLAY_NAME))
                        FileInputStream(sourceFile).use { fis -> fis.copyTo(zos) }
                        zos.closeEntry()

                        zos.putNextEntry(ZipEntry("README_INSTALL.txt"))
                        zos.write("CHAT 100m WITHOUT NETWORK - Project & APK Bundle\nInstall the APK and enjoy 100m offline chat!\n".toByteArray())
                        zos.closeEntry()
                    }
                }
                true
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null) {
                    downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, ZIP_DISPLAY_NAME)
                    FileOutputStream(targetFile).use { fos ->
                        ZipOutputStream(fos).use { zos ->
                            zos.putNextEntry(ZipEntry(APK_DISPLAY_NAME))
                            FileInputStream(sourceFile).use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
