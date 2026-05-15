/**
Copyright 2020 ZynkSoftware SRL

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */


package com.zynksoftware.documentscanner.ui.imagecrop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.zynksoftware.documentscanner.R
import com.zynksoftware.documentscanner.common.extensions.scaledBitmap
import com.zynksoftware.documentscanner.common.utils.OpenCvNativeBridge
import com.zynksoftware.documentscanner.databinding.FragmentImageCropBinding
import com.zynksoftware.documentscanner.model.DocumentScannerErrorModel
import com.zynksoftware.documentscanner.ui.base.BaseFragment
import com.zynksoftware.documentscanner.ui.scan.InternalScanActivity
import id.zelory.compressor.determineImageRotation

internal class ImageCropFragment : BaseFragment() {
    private var _binding: FragmentImageCropBinding? = null
    private val binding get() = _binding!!

    companion object {
        private val TAG = ImageCropFragment::class.simpleName
        private const val ZOOM_SCALE = 4f
        private const val ZOOM_SECTION_RATIO = 0.3f
        private const val ZOOM_MARGIN_RATIO = 0.025f
        private const val ZOOM_DEAD_ZONE_RATIO = 0.2f

        fun newInstance(): ImageCropFragment {
            return ImageCropFragment()
        }
    }

    private val nativeClass = OpenCvNativeBridge()

    private var selectedImage: Bitmap? = null
    private var zoomSectionWidthPx = 0
    private var zoomSectionHeightPx = 0
    private var zoomMarginXPx = 0
    private var zoomMarginYPx = 0
    private var previewOnRightSide: Boolean? = null
    private var previewOnBottomSide: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageCropBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.holderImageView.post {
            initializeZoomPreviewSize()
        }

        val sourceBitmap =
            BitmapFactory.decodeFile(getScanActivity().originalImageFile.absolutePath)
        if (sourceBitmap != null) {
            selectedImage =
                determineImageRotation(getScanActivity().originalImageFile, sourceBitmap)
        } else {
            Log.e(TAG, DocumentScannerErrorModel.ErrorMessage.INVALID_IMAGE.error)
            onError(DocumentScannerErrorModel(DocumentScannerErrorModel.ErrorMessage.INVALID_IMAGE))
            Handler(Looper.getMainLooper()).post {
                closeFragment()
            }
        }
        binding.holderImageView.post {
            initializeCropping()
        }

        initListeners()
    }

    private fun initListeners() {
        binding.closeButton.setOnClickListener {
            closeFragment()
        }
        binding.confirmButton.setOnClickListener {
            onConfirmButtonClicked()
        }
        binding.polygonView.onCornerTouchEvent = { action, rawX, rawY ->
            onPolygonCornerTouch(action, rawX, rawY)
        }
    }

    private fun initializeZoomPreviewSize() {
        val containerWidth = binding.holderImageView.width
        val containerHeight = binding.holderImageView.height
        if (containerWidth <= 0 || containerHeight <= 0) {
            return
        }

        val largerDimensionPx = maxOf(containerWidth, containerHeight)
        val zoomSectionSizePx = (largerDimensionPx * ZOOM_SECTION_RATIO).toInt().coerceAtLeast(1)
        zoomSectionWidthPx = zoomSectionSizePx
        zoomSectionHeightPx = zoomSectionSizePx
        zoomMarginXPx = (containerWidth * ZOOM_MARGIN_RATIO).toInt()
        zoomMarginYPx = (containerHeight * ZOOM_MARGIN_RATIO).toInt()

        binding.zoomPreviewContainer.layoutParams = FrameLayout.LayoutParams(
            zoomSectionWidthPx,
            zoomSectionHeightPx
        )
        binding.zoomPreviewContainer.translationX = zoomMarginXPx.toFloat()
        binding.zoomPreviewContainer.translationY = zoomMarginYPx.toFloat()
    }

    private fun onPolygonCornerTouch(action: Int, rawX: Float, rawY: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (zoomSectionWidthPx == 0 || zoomSectionHeightPx == 0) {
                    initializeZoomPreviewSize()
                }
                updateZoomPreviewPosition(rawX, rawY)
                updateZoomPreviewImage(rawX, rawY)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hideZoomPreview()
            }
        }
    }

    private fun updateZoomPreviewPosition(rawX: Float, rawY: Float) {
        val hostLocation = IntArray(2)
        binding.holderImageView.getLocationOnScreen(hostLocation)

        val localTouchX = rawX - hostLocation[0]
        val localTouchY = rawY - hostLocation[1]
        val width = binding.holderImageView.width.toFloat()
        val height = binding.holderImageView.height.toFloat()
        val switchStartRatio = (1f - ZOOM_DEAD_ZONE_RATIO) / 2f
        val leftSwitchX = width * switchStartRatio
        val rightSwitchX = width * (1f - switchStartRatio)
        val topSwitchY = height * switchStartRatio
        val bottomSwitchY = height * (1f - switchStartRatio)

        if (localTouchX <= leftSwitchX) {
            previewOnRightSide = true
        } else if (localTouchX >= rightSwitchX) {
            previewOnRightSide = false
        } else if (previewOnRightSide == null) {
            previewOnRightSide = localTouchX < width / 2f
        }

        if (localTouchY <= topSwitchY) {
            previewOnBottomSide = true
        } else if (localTouchY >= bottomSwitchY) {
            previewOnBottomSide = false
        } else if (previewOnBottomSide == null) {
            previewOnBottomSide = localTouchY < height / 2f
        }

        val previewX = if (previewOnRightSide == true) {
            (binding.holderImageView.width - zoomSectionWidthPx - zoomMarginXPx).toFloat()
        } else {
            zoomMarginXPx.toFloat()
        }

        val previewY = if (previewOnBottomSide == true) {
            (binding.holderImageView.height - zoomSectionHeightPx - zoomMarginYPx).toFloat()
        } else {
            zoomMarginYPx.toFloat()
        }

        binding.zoomPreviewContainer.translationX = previewX
        binding.zoomPreviewContainer.translationY = previewY
    }

    private fun updateZoomPreviewImage(rawX: Float, rawY: Float) {
        val drawable = binding.imagePreview.drawable as? BitmapDrawable ?: return
        val imageBitmap = drawable.bitmap ?: return
        if (binding.imagePreview.width <= 0 || binding.imagePreview.height <= 0) {
            return
        }

        val imageLocation = IntArray(2)
        binding.imagePreview.getLocationOnScreen(imageLocation)
        val imageLeft = imageLocation[0].toFloat()
        val imageTop = imageLocation[1].toFloat()
        val imageRight = imageLeft + binding.imagePreview.width
        val imageBottom = imageTop + binding.imagePreview.height

        if (rawX < imageLeft || rawX > imageRight || rawY < imageTop || rawY > imageBottom) {
            hideZoomPreview()
            return
        }

        val normalizedX = ((rawX - imageLeft) / binding.imagePreview.width).coerceIn(0f, 1f)
        val normalizedY = ((rawY - imageTop) / binding.imagePreview.height).coerceIn(0f, 1f)
        val sourceX = (normalizedX * imageBitmap.width).toInt().coerceIn(0, imageBitmap.width - 1)
        val sourceY =
            (normalizedY * imageBitmap.height).toInt().coerceIn(0, imageBitmap.height - 1)

        val sampleWidth = (zoomSectionWidthPx / ZOOM_SCALE).toInt().coerceAtLeast(1)
        val sampleHeight = (zoomSectionHeightPx / ZOOM_SCALE).toInt().coerceAtLeast(1)
        val cropWidth = minOf(sampleWidth, imageBitmap.width)
        val cropHeight = minOf(sampleHeight, imageBitmap.height)

        val cropLeft = (sourceX - cropWidth / 2).coerceIn(0, imageBitmap.width - cropWidth)
        val cropTop = (sourceY - cropHeight / 2).coerceIn(0, imageBitmap.height - cropHeight)

        val croppedBitmap = Bitmap.createBitmap(imageBitmap, cropLeft, cropTop, cropWidth, cropHeight)
        val zoomedBitmap = Bitmap.createScaledBitmap(
            croppedBitmap,
            zoomSectionWidthPx,
            zoomSectionHeightPx,
            true
        )

        binding.zoomPreviewImage.setImageBitmap(zoomedBitmap)
        binding.zoomPreviewContainer.visibility = View.VISIBLE
    }

    private fun hideZoomPreview() {
        binding.zoomPreviewContainer.visibility = View.GONE
        previewOnRightSide = null
        previewOnBottomSide = null
    }

    private fun getScanActivity(): InternalScanActivity {
        return (requireActivity() as InternalScanActivity)
    }

    private fun initializeCropping() {
        if (selectedImage != null && selectedImage!!.width > 0 && selectedImage!!.height > 0) {
            val scaledBitmap: Bitmap =
                selectedImage!!.scaledBitmap(
                    binding.holderImageCrop.width,
                    binding.holderImageCrop.height
                )
            binding.imagePreview.setImageBitmap(scaledBitmap)
            val tempBitmap = (binding.imagePreview.drawable as BitmapDrawable).bitmap
            val pointFs = getEdgePoints(tempBitmap)
            Log.d(TAG, "ZDCgetEdgePoints ends ${System.currentTimeMillis()}")
            binding.polygonView.setPoints(pointFs)
            binding.polygonView.visibility = View.VISIBLE
            val padding = resources.getDimension(R.dimen.zdc_polygon_dimens).toInt()
            val layoutParams =
                FrameLayout.LayoutParams(tempBitmap.width + padding, tempBitmap.height + padding)
            layoutParams.gravity = Gravity.CENTER
            binding.polygonView.layoutParams = layoutParams
        }
    }

    private fun onError(error: DocumentScannerErrorModel) {
        if (isAdded) {
            getScanActivity().onError(error)
        }
    }

    private fun onConfirmButtonClicked() {
        getCroppedImage()
        startImageProcessingFragment()
    }

    private fun getEdgePoints(tempBitmap: Bitmap): Map<Int, PointF> {
        Log.d(TAG, "ZDCgetEdgePoints Starts ${System.currentTimeMillis()}")
        val pointFs: List<PointF> = nativeClass.getContourEdgePoints(tempBitmap)
        return binding.polygonView.getOrderedValidEdgePoints(tempBitmap, pointFs)
    }

    private fun getCroppedImage() {
        if (selectedImage != null) {
            try {
                Log.d(TAG, "ZDCgetCroppedImage starts ${System.currentTimeMillis()}")
                val points: Map<Int, PointF> = binding.polygonView.getPoints()
                val xRatio: Float = selectedImage!!.width.toFloat() / binding.imagePreview.width
                val yRatio: Float = selectedImage!!.height.toFloat() / binding.imagePreview.height
                val pointPadding =
                    requireContext().resources.getDimension(R.dimen.zdc_point_padding).toInt()
                val x1: Float = (points.getValue(0).x + pointPadding) * xRatio
                val x2: Float = (points.getValue(1).x + pointPadding) * xRatio
                val x3: Float = (points.getValue(2).x + pointPadding) * xRatio
                val x4: Float = (points.getValue(3).x + pointPadding) * xRatio
                val y1: Float = (points.getValue(0).y + pointPadding) * yRatio
                val y2: Float = (points.getValue(1).y + pointPadding) * yRatio
                val y3: Float = (points.getValue(2).y + pointPadding) * yRatio
                val y4: Float = (points.getValue(3).y + pointPadding) * yRatio
                getScanActivity().croppedImage =
                    nativeClass.getScannedBitmap(selectedImage!!, x1, y1, x2, y2, x3, y3, x4, y4)
                Log.d(TAG, "ZDCgetCroppedImage ends ${System.currentTimeMillis()}")
            } catch (e: java.lang.Exception) {
                Log.e(TAG, DocumentScannerErrorModel.ErrorMessage.CROPPING_FAILED.error, e)
                onError(
                    DocumentScannerErrorModel(
                        DocumentScannerErrorModel.ErrorMessage.CROPPING_FAILED,
                        e
                    )
                )
            }
        } else {
            Log.e(TAG, DocumentScannerErrorModel.ErrorMessage.INVALID_IMAGE.error)
            onError(DocumentScannerErrorModel(DocumentScannerErrorModel.ErrorMessage.INVALID_IMAGE))
        }
    }

    private fun startImageProcessingFragment() {
        getScanActivity().showImageProcessingFragment()
    }

    private fun closeFragment() {
        getScanActivity().closeCurrentFragment()
    }

    override fun configureEdgeToEdgeInsets(insets: WindowInsetsCompat) {
        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

        with(binding) {
            holderImageView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBarsInsets.top
            }

            bottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBarsInsets.bottom
            }
        }
    }
}
