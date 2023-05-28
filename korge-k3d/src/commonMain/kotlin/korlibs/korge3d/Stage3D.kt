package korlibs.korge3d

import korlibs.korge.render.RenderContext
import korlibs.korge.view.Container
import korlibs.korge.view.View
import korlibs.korge.view.Views
import korlibs.korge.view.addTo
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.color.setToColorPremultiplied


inline fun Container.scene3D(views: Views3D = Views3D(stage!!.views), callback: Stage3D.() -> Unit = {}): Stage3DView {
    val stage3D = Stage3D(views)
    val view = Stage3DView(stage3D)
    view.addTo(this)
    stage3D.apply(callback)
    return view
}


class Views3D(val views: Views) {
}


class Stage3D(val views: Views3D) : Container3D() {
	lateinit var view: Stage3DView
	//var ambientColor: RGBA = Colors.WHITE
	var ambientColor: RGBA = Colors.BLACK // No ambient light
	var ambientPower: Float = 0.3f
	var camera: Camera3D = Camera3D.Perspective().apply {
        this.root = this@Stage3D
		//positionLookingAt(0, 1, 10, 0, 0, 0)
	}
}


class Stage3DView(val stage3D: Stage3D) : View() {
	init {
		stage3D.view = this
	}

	private val ctx3D = RenderContext3D()
	override fun renderInternal(ctx: RenderContext) {
		ctx.flush()
		ctx.clear(depth = 1f, clearColor = false)
		//ctx.ag.clear(color = Colors.RED)
		ctx3D.ag = ctx.ag
		ctx3D.rctx = ctx
		ctx3D.projMat.copyFrom(stage3D.camera.getProjMatrix(ctx.backWidth.toFloat(), ctx.backHeight.toFloat()))
		ctx3D.cameraMat.copyFrom(stage3D.camera.transform.matrix)
		ctx3D.ambientColor.setToColorPremultiplied(stage3D.ambientColor).scale(stage3D.ambientPower)
		ctx3D.cameraMatInv.invert(stage3D.camera.transform.matrix)
		ctx3D.projCameraMat.multiply(ctx3D.projMat, ctx3D.cameraMatInv)
		ctx3D.lights.clear()
		stage3D.foreachDescendant {
			if (it is Light3D) {
				if (it.active) ctx3D.lights.add(it)
			}
		}
		stage3D.render(ctx3D)
	}
}
