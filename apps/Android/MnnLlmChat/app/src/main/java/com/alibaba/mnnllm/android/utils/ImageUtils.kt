package com.alibaba.mnnllm.android.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.alibaba.mnnllm.android.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ImageUtils {
    private const val TAG = "ImageUtils"

    fun showImageMenu(context: Context, imageUri: Uri, showShareOption: Boolean = true) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_image_menu, null)

        view.findViewById<View>(R.id.btn_save_image).setOnClickListener {
            dialog.dismiss()
            val success = saveImageToGallery(context, imageUri)
            if (success) {
                Toast.makeText(context, R.string.image_saved_to_gallery, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.failed_to_save_image, Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.btn_share_image).setOnClickListener {
            dialog.dismiss()
            shareImage(context, imageUri)
        }
        view.findViewById<View>(R.id.btn_share_image).visibility =
            if (showShareOption) View.VISIBLE else View.GONE

        dialog.show()
        dialog.setContentView(view)
    }

    fun shareImage(context: Context, imageUri: Uri) {
        val shareUri = if (imageUri.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                File(imageUri.path!!)
            )
        } else {
            imageUri
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_image)))
    }

    fun saveImageToGallery(context: Context, imageUri: Uri): Boolean {
        val fileName = "MNN_Chat_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver

        // Create content values for the new image
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MNN-Chat")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        // Insert the new image into MediaStore
        val galleryUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return false

        return try {
            // For file:// URI, use FileInputStream for better compatibility
            if (imageUri.scheme == "file") {
                val filePath = imageUri.path
                if (filePath != null) {
                    File(filePath).inputStream().use { inputStream ->
                        resolver.openOutputStream(galleryUri)?.use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } else {
                    return false
                }
            } else {
                // For content:// and other URIs, use ContentResolver
                resolver.openInputStream(imageUri)?.use { inputStream ->
                    resolver.openOutputStream(galleryUri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            // Mark as not pending if on Android Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(galleryUri, contentValues, null, null)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to gallery", e)
            // Delete the failed entry
            try {
                resolver.delete(galleryUri, null, null)
            } catch (deleteException: Exception) {
                Log.e(TAG, "Failed to delete partial image", deleteException)
            }
            false
        }
    }

    /**
     * Resizes and compresses an image file.
     * @param file The image file to compress.
     * @param maxDimension The maximum width or height of the compressed image.
     * @param quality The JPEG compression quality (0-100).
     */
    fun compressImageFile(file: java.io.File, maxDimension: Int = 1024, quality: Int = 80) {
        try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)

            var inSampleSize = 1
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= maxDimension && halfWidth / inSampleSize >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options) ?: return

            // Handle orientation
            val exif = try {
                android.media.ExifInterface(file.absolutePath)
            } catch (e: Exception) {
                null
            }
            val orientation = exif?.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_UNDEFINED
            ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }

            val rotatedBitmap = if (!matrix.isIdentity) {
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val finalBitmap = if (rotatedBitmap.width > maxDimension || rotatedBitmap.height > maxDimension) {
                val ratio = rotatedBitmap.width.toFloat() / rotatedBitmap.height.toFloat()
                val targetWidth: Int
                val targetHeight: Int
                if (ratio > 1) {
                    targetWidth = maxDimension
                    targetHeight = (maxDimension / ratio).toInt()
                } else {
                    targetHeight = maxDimension
                    targetWidth = (maxDimension * ratio).toInt()
                }
                android.graphics.Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, targetHeight, true)
            } else {
                rotatedBitmap
            }

            java.io.FileOutputStream(file).use { out ->
                finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            }
            
            if (finalBitmap != rotatedBitmap && finalBitmap != bitmap) {
                finalBitmap.recycle()
            }
            if (rotatedBitmap != bitmap) {
                rotatedBitmap.recycle()
            }
            bitmap.recycle()
            
            Log.d(TAG, "Image compressed: ${file.length() / 1024} KB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
        }
    }
}
