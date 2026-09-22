package com.jaewon.brushalarm

import android.app.Activity
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions

/** Android/ML boundary; the verifier and all policy decisions remain pure Kotlin. */
interface FaceMeshProcessor : AutoCloseable {
    fun warmUp(onReady: () -> Unit, onFailure: (Exception) -> Unit)

    fun process(
        bitmap: Bitmap,
        onResult: (MeshObservation?) -> Unit,
        onFailure: (Exception) -> Unit,
        onComplete: () -> Unit,
    )
}

class MlKitFaceMeshProcessor(private val activity: Activity) : FaceMeshProcessor {
    private val detector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build(),
    )

    override fun warmUp(onReady: () -> Unit, onFailure: (Exception) -> Unit) {
        val inputSize = FaceMeshInputSize.minimum()
        val warmupBitmap = Bitmap.createBitmap(inputSize.width, inputSize.height, Bitmap.Config.ARGB_8888)
        detector.process(InputImage.fromBitmap(warmupBitmap, 0))
            .addOnSuccessListener(activity) { onReady() }
            .addOnFailureListener(activity, onFailure)
            // Cleanup must run even after Activity-scoped UI listeners are detached.
            .addOnCompleteListener { warmupBitmap.recycle() }
    }

    override fun process(
        bitmap: Bitmap,
        onResult: (MeshObservation?) -> Unit,
        onFailure: (Exception) -> Unit,
        onComplete: () -> Unit,
    ) {
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(activity) { meshes ->
                val mesh = meshes.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (mesh == null) {
                    onResult(null)
                } else {
                    val box = mesh.boundingBox
                    onResult(
                        FaceMeshObservationMapper.map(
                            faceBounds = PixelRect(
                                box.left.toDouble(), box.top.toDouble(),
                                box.right.toDouble(), box.bottom.toDouble(),
                            ),
                            points = mesh.allPoints.map { point ->
                                IndexedMeshPoint(
                                    point.index,
                                    point.position.x.toDouble(),
                                    point.position.y.toDouble(),
                                )
                            },
                        ),
                    )
                }
            }
            .addOnFailureListener(activity, onFailure)
            // The caller releases the inference gate and bitmap here without touching UI.
            .addOnCompleteListener { onComplete() }
    }

    override fun close() = detector.close()
}
