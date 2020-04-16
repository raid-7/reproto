package raid.neuroide.reproto.crdt.seq

import kotlinx.serialization.Serializable
import raid.neuroide.reproto.common.IndexedSet
import raid.neuroide.reproto.common.indexedSetOf
import raid.neuroide.reproto.crdt.LocalSiteId
import raid.neuroide.reproto.crdt.ObservableCrdt
import raid.neuroide.reproto.crdt.Operation


@Serializable
class Sequence(private val siteId: LocalSiteId, private val strategy: AllocationStrategy) : ObservableCrdt<Change>() {
    private val elements: IndexedSet<Element> = indexedSetOf(Element(LeftId), Element(RightId))

    val content: List<String>
        get() = elements.mapNotNull {
            if (it.pid == LeftId || it.pid == RightId)
                null
            else
                it.value
        }

    val size: Int
        get() = elements.size - 2

    operator fun get(index: Int): String {
        if (index >= size)
            throw IndexOutOfBoundsException()
        return content[index + 1]
    }

    fun insert(index: Int, content: String) {
        checkLimits(index, true)

        val lId = elements[index].pid
        val rId = elements[index + 1].pid

        val newId = allocateIdentifier(lId, rId)
        val op = SequenceOperationInsert(newId, content)
        commitLocallyGenerated(op)
    }

    fun delete(index: Int) {
        checkLimits(index)
        val id = elements[index + 1].pid

        val op = SequenceOperationDelete(id)
        commitLocallyGenerated(op)
    }

    fun move(from: Int, to: Int) {
        checkLimits(from)
        checkLimits(to, true)

        val fromId = elements[from + 1].pid
        val toLId = elements[to].pid
        val toRId = elements[to + 1].pid

        val newId = allocateIdentifier(toLId, toRId)
        val op = SequenceOperationMove(fromId, newId)
        commitLocallyGenerated(op)
    }

    private fun commitLocallyGenerated(op: SequenceOperation) {
        deliver(op)
        myUpstream?.deliver(op)
    }

    private fun checkLimits(index: Int, allowEnd: Boolean = false) {
        val rightLimit = if (allowEnd) size else size - 1
        if (index !in 0..rightLimit)
            throw IndexOutOfBoundsException()
    }

    private fun allocateIdentifier(left: Identifier, right: Identifier): Identifier {
        val upstream = myUpstream ?: throw IllegalStateException("Upstream is required to generate identifier")
        val position = strategy.allocatePosition(left.position, right.position, siteId.id)
        return Identifier(position, upstream.nextLocalIndex())
    }

    override fun deliver(op: Operation) {
        when (val operation = op as SequenceOperation) {
            is SequenceOperationInsert -> {
                val (pid, content) = operation
                val element = Element(pid, content)
                val isAdded = elements.add(element)
                if (isAdded) {
                    fire(Change.Insert(elements.indexOf(element) - 1, content))
                }
            }
            is SequenceOperationDelete -> {
                val index = elements.indexOf(Element(operation.pid))
                if (index >= 0) {
                    val content = elements.removeAt(index).value
                    fire(Change.Delete(index - 1, content))
                }
            }
            is SequenceOperationMove -> {
                val fromIndex = elements.indexOf(Element(operation.pidFrom))
                if (fromIndex >= 0) {
                    val content = elements[fromIndex].value
                    val newElement = Element(operation.pidTo, content)

                    elements.removeAt(fromIndex)
                    val toIndex = elements.addIndexed(newElement)

                    fire(Change.Move(fromIndex - 1, toIndex - 1, content))
                }
            }
            else -> return
        }
    }

    @Serializable
    private class Element(val pid: Identifier, val value: String) : Comparable<Element> {
        constructor(pid : Identifier) : this(pid, "")

        override fun compareTo(other: Element): Int {
            return compareValues(pid, other.pid)
        }
    }
}
