package korlibs.korge3d.format.gltf2

import korlibs.datastructure.iterators.*
import korlibs.graphics.*
import korlibs.graphics.shader.*
import korlibs.korge3d.*
import korlibs.korge3d.material.*
import korlibs.math.geom.*
import korlibs.memory.*
import korlibs.time.*

fun Container3D.gltf2View(gltf: GLTF2) = GLTF2View(gltf).addTo(this)

class GLTF2View(override var gltf: GLTF2) : Container3D(), GLTF2Holder {
    val nodeToViews = LinkedHashMap<GLTF2.Node, GLTF2ViewNode>()

    init {
        for (scene in gltf.scenes) {
            for (node in scene.childrenNodes) {
                val view = GLTF2ViewNode(gltf, node)
                addChild(view)
                nodeToViews[node] = view
            }
        }

        addUpdater(first = false) {
            //println("updater!")
            updateAnimationDelta(it)
        }
    }

    var currentTime = 0.seconds
    fun updateAnimationDelta(dt: TimeSpan) {
        currentTime += dt
        updateAnimation(currentTime)
    }

    fun updateAnimation(time: TimeSpan) {
        for (animation in gltf.animations) {
            for (channel in animation.channels) {
                val target = channel.target ?: continue
                val node = gltf.nodes[target.node]
                val view = nodeToViews[node] ?: continue
                val sampler = animation.samplers[channel.sampler]
                val maxTime = sampler.maxTime(gltf)
                val currentTime = time.seconds.toFloat()
                val rtime = currentTime % maxTime

                when (target.path) {
                    "weights" -> {
                        view.meshView?.primitiveViews?.fastForEach {
                            it.weights = sampler.doLookup(gltf, rtime, it.nweights).toVector4()
                        }
                    }
                    else -> {
                        println("Unsupported animation target.path=${target.path}")
                    }
                }
            }
        }
    }
}

class GLTF2ViewNode(override val gltf: GLTF2, val node: GLTF2.Node) : Container3D(), GLTF2Holder {
    val meshView = if (node.mesh >= 0) GLTF2ViewMesh(gltf, gltf.meshes[node.mesh]).addTo(this) else null
    init {
        transform.setMatrix(node.mmatrix.mutable)
        for (child in node.childrenNodes) {
            addChild(GLTF2ViewNode(gltf, child))
        }
    }
}

class GLTF2ViewMesh(val gltf: GLTF2, val mesh: GLTF2.Mesh) : Container3D() {
    val primitiveViews = mesh.primitives.map {
        GLTF2ViewPrimitive(gltf, it, mesh).addTo(this)
    }
}

class GLTF2ViewPrimitive(override val gltf: GLTF2, val primitive: GLTF2.Primitive, val mesh: GLTF2.Mesh) : ViewWithMaterial3D(), GLTF2Holder {
    val nweights get() = primitive.targets.size
    var weights = mesh.weightsVector
    val drawType: AGDrawType get() = primitive.drawType

    fun genAGVertexData(prim: GLTF2.PrimitiveAttribute, index: Int, targetIndex: Int): AGVertexData {
        val accessor = gltf.accessors[index]
        val bufferView = gltf.bufferViews[accessor.bufferView]
        val buffer = bufferView.slice(gltf).slice(accessor.byteOffset)
        val att = when {
            prim.isPosition -> Shaders3D.a_pos
            prim.isNormal -> Shaders3D.a_nor
            prim.isTangent -> if (accessor.ncomponent == 3) Shaders3D.a_tan3 else Shaders3D.a_tan
            prim.isTexcoord0 -> Shaders3D.a_tex
            else -> TODO("${prim}")
        }
        val expectedComponents = when (att) {
            Shaders3D.a_pos, Shaders3D.a_nor -> 3
            Shaders3D.a_tan3 -> 3
            Shaders3D.a_tan -> 4
            Shaders3D.a_tex -> 2
            else -> TODO("Unsupported $att")
        }
        check(accessor.ncomponent == expectedComponents) { "$prim in $accessor expected to have $expectedComponents components but had ${accessor.ncomponent}" }

        when (att) {
            Shaders3D.a_pos, Shaders3D.a_nor -> check(accessor.componentTType == VarKind.TFLOAT)
            Shaders3D.a_tan, Shaders3D.a_tan3 -> check(accessor.componentTType == VarKind.TFLOAT)
            Shaders3D.a_tex -> check(accessor.componentTType == VarKind.TFLOAT)
            else -> TODO("Unsupported $att")
        }

        val ratt = when (att) {
            Shaders3D.a_pos -> if (targetIndex < 0) Shaders3D.a_pos else Shaders3D.a_posTarget[targetIndex]
            Shaders3D.a_tan -> if (targetIndex < 0) Shaders3D.a_tan else Shaders3D.a_tanTarget[targetIndex]
            Shaders3D.a_nor -> if (targetIndex < 0) Shaders3D.a_nor else Shaders3D.a_norTarget[targetIndex]
            else -> att
        }

        return AGVertexData(VertexLayout(ratt), buffer = AGBuffer().also { it.upload(buffer) })
    }

    val vertexData: AGVertexArrayObject = AGVertexArrayObject(
        *primitive.attributes.map { (prim, index) -> genAGVertexData(prim, index, -1) }.toTypedArray(),
        *primitive.targets.flatMapIndexed { targetIndex, map ->
            map.map { (prim, index) -> genAGVertexData(prim, index, targetIndex) }
        }.toTypedArray(),
    )

    // @TODO:
    val indexAccessor: GLTF2.Accessor = gltf.accessors[primitive.indices]
    val indexType: AGIndexType = indexAccessor.asIndexType()
    val indexDataOffset = 0
    val indexSlice = indexAccessor.bufferView(gltf).slice(gltf).slice(indexAccessor.byteOffset)
    val indexData = AGBuffer().also { it.upload(indexSlice) }
    val vertexCount = indexSlice.sizeInBytes / indexType.bytesSize

    //val meshMaterial = Material3D(diffuse = Material3D.LightTexture(crateTex))
    //override val material = Material3D(diffuse = Material3D.LightColor(Colors.RED.withAd(0.5)))
    override val material = gltf.materials3D[primitive.material]

    override fun putUniforms(ctx: RenderContext3D) {
        super.putUniforms(ctx)
        ctx.rctx[Shaders3D.WeightsUB].push {
            it[u_Weights] = this@GLTF2ViewPrimitive.weights
        }
    }

    override fun render(ctx: RenderContext3D) {
        //ctx[DefaultShaders.ProjViewUB].push {
        //    val width = 100f
        //    val height = 100f
        //    it[this.u_ProjMat] = Matrix4.perspective(45.degrees, width / height, 1f, 1000f)
        //    it[this.u_ViewMat] = Matrix4()
        //}

        //val program = ctx.shaders.getProgram3D(
        //val program = PROGRAM ?: ctx.shaders.getProgram3D(
        val program = ctx.shaders.getProgram3D(
            ctx.lights.size.clamp(0, 4),
            nweights,
            material,
            material.hasTexture
            //mesh.hasTexture
        )
        putUniforms(ctx)

        //println("uniformBlocks=$uniformBlocks")

        //println("drawType=$drawType")
        //println("vertexData=$vertexData")

        ctx.ag.draw(
            AGBatch(
                frameBuffer = ctx.rctx.currentFrameBuffer.base,
                frameBufferInfo = ctx.rctx.currentFrameBuffer.info,
                drawType = drawType,
                indexType = indexType,
                indices = indexData,
                vertexData = vertexData,
                program = program,
                uniformBlocks = ctx.rctx.createCurrentUniformsRef(program),
                textureUnits = textureUnits,
                vertexCount = vertexCount,
                drawOffset = 0,
                depthAndFrontFace = DEFAULT_DEPTH_FUNC,
            )
        )
    }

    companion object {
    }
}

//fun createMeshPrimitive(primitive: GLTF2.GPrimitive) {
//    AGVertexArrayObject(AGVertexData())
//    primitive.attributes.map {
//        when (it.key) {
//            GLTF2.GAttribute.POSITION -> Shaders3D.a_pos
//            else -> TODO("${it.key}")
//        }
//    }
//}

