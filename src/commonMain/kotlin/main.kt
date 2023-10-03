import korlibs.korge.*
import korlibs.korge.scene.*
import korlibs.korge.view.*
import korlibs.image.color.*
import korlibs.io.async.*
import korlibs.korge.input.*
import korlibs.korge.tween.*
import korlibs.korge.tween.tween
import korlibs.korge.view.align.*
import korlibs.math.geom.*
import korlibs.math.interpolation.*
import korlibs.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.coroutines.*
import kotlin.math.*

suspend fun main() = Korge(windowSize = Size(1500, 900), backgroundColor = Colors["#2b2b2b"]) {
    val sceneContainer = sceneContainer()
    sceneContainer.changeTo { MyScene() }
}

data class Tile(val view: View, var hp: Int, val itemType: ItemType)
data class Item(val view: View, val type: ItemType, val amount: Int)

enum class ItemType {
    StoneChunk,
    StoneBrick,
}

data class ItemStack(val type: ItemType, val amount: Int)

operator fun ItemType.times(amount: Int) = ItemStack(this, amount)

interface Building {
    val view: View
    val acceptRadius: Int
    fun onDestroy() = Unit
    fun accepts(item: Item): Boolean = false
    fun accept(item: Item) = Unit
    data class Core(override val view: View) : Building {
        override fun accepts(item: Item) = true // for now
        override val acceptRadius = 50
        val inventory = Inventory()
        override fun accept(item: Item) {
            inventory.add(item.type, item.amount)
        }
    }
}

class Inventory {
    private val counts = IntArray(ItemType.values().size)
    fun add(type: ItemType, amount: Int) {
        counts[type.ordinal] += amount
    }

    fun remove(type: ItemType, amount: Int) {
        counts[type.ordinal] -= amount
    }

    fun has(type: ItemType, amount: Int) = counts[type.ordinal] >= amount
    operator fun get(type: ItemType) = counts[type.ordinal]
}

class MyScene : Scene() {
    private val tileSize = 64.0
    private val tileBorderWidth = 1.0
    private val itemSize = 55.0
    private val initialTileHp = 10
    private val tiles = mutableListOf<Tile>()
    private val items = mutableListOf<Item>()
    private val buildings = mutableListOf<Building>()

    override suspend fun SContainer.sceneMain() {
        // TODO: find a way to clear the frame before drawing so we dont need this background object
        solidRect(size, Colors.BLACK)

        buildings += createCoreBuilding(vec(900f, 300f))
        buildings += createBuilding(
            vec(900f, 600f),
            input = ItemType.StoneChunk * 1,
            maxInputAmount = 3,
            output = ItemType.StoneBrick * 10,
            maxOutputAmount = 100,
            coroutineContext = coroutineContext,
        )

        for (x in 0 until 10) {
            for (y in 0 until 10) {
                val tileView = container {
                    position(x * tileSize, y * tileSize)
                    solidRect(tileSize, tileSize, Colors.DARKGREEN) {
                        center()
                    }
                    val innerSize = tileSize - tileBorderWidth
                    solidRect(innerSize, innerSize, Colors.GREEN) {
                        center()
                    }
                }
                val tile = Tile(tileView, initialTileHp, ItemType.StoneChunk)
                tiles += tile
                tileView.onClick {
                    tile.hp -= 1
                    if (tile.hp <= 0) {
                        val itemView = container {
                            position(tileView.pos)
                            solidRect(itemSize, itemSize, Colors.BLUE) { center() }
                            rotation((0 until 360).random().degrees)
                        }
                        val item = Item(itemView, tile.itemType, 1)
                        items += item
                        var isDragging: Boolean
                        itemView.draggable { drag ->
                            if (drag.start) launchImmediately {
                                isDragging = true
                                while (isDragging) tween(
                                    itemView::rotation[(0 until 360).random().degrees],
                                    time = 0.5.seconds,
                                    easing = Easing.EASE_IN_OUT_QUAD,
                                )
                            }
                            if (drag.end) {
                                isDragging = false
                                // TODO: optimize performance
                                val b = buildings.find {
                                    val squareDistance = (it.view.pos - itemView.pos).lengthSquared
                                    squareDistance <= it.acceptRadius * it.acceptRadius && it.accepts(item)
                                }
                                if (b != null) {
                                    items -= item
                                    removeChild(itemView)
                                    b.accept(item)
                                }
                            }
                        }

                        removeChild(tileView)
                        tiles.remove(tile) // TODO: improve performance later on
                    }
                }
            }
        }
    }
}

fun Container.createCoreBuilding(targetPosition: Point): Building {
    val buildingView = container {
        position(targetPosition)
        solidRect(100, 100) { center() }
    }
    return Building.Core(buildingView)
}

fun Container.createBuilding(
    targetPosition: Point,
    size: Size = Size(100, 100),
    color: RGBA = Colors.LIGHTCORAL,
    input: ItemStack,
    maxInputAmount: Int,
    output: ItemStack,
    maxOutputAmount: Int,
    coroutineContext: CoroutineContext,
): Building {
    val buildingView = container {
        position(targetPosition)
        solidRect(size, Colors.WHITE) { center() }
        solidRect(size - Size(2, 2), color) { center() }
    }
    val progressBar = buildingView.solidRect(0f, 5f, Colors.YELLOW) {
        position(-size.width / 2, -size.height / 2)
    }
    var inputAmount = 0
    var outputAmount = 0
    val productionChannel = Channel<Unit>(0)
    val productionJob = launchImmediately(coroutineContext) {
        while (true) {
            productionChannel.receive()
            if (inputAmount < input.amount) continue
            if (maxOutputAmount - outputAmount < output.amount) continue
            inputAmount -= input.amount // TODO: update visuals
            progressBar.width = size.width
            tween(
                progressBar::width[0f],
                time = 10.seconds,
                easing = Easing.LINEAR,
            )
            outputAmount += output.amount // TODO: update visuals
        }
    }
    return object : Building {
        override val acceptRadius = maxOf(size.width, size.height).roundToInt()
        override val view = buildingView

        override fun accepts(item: Item) =
            item.type == input.type && maxInputAmount - inputAmount >= item.amount

        override fun accept(item: Item) {
            require(accepts(item))
            inputAmount += item.amount
            productionChannel.trySend(Unit)
        }

        override fun onDestroy() {
            productionJob.cancel()
            productionChannel.close()
        }
    }
}

fun View.withResourceDragAndDrop() {
    var items: ItemStack? = null
    onMouseDrag {
        if (it.start) items
    }
}
