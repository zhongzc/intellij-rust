/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapiext.isWriteAccessAllowed
import gnu.trove.THashMap
import org.rust.RsTask.TaskType.*
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.crateGraph
import org.rust.openapiext.checkReadAccessAllowed
import org.rust.openapiext.executeUnderProgress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Possible modifications:
 * - After IDE restart: full recheck (for each crate compare [CrateMetaData] and `modificationStamp` of each file).
 *   Tasks [CARGO_SYNC] and [MACROS_UNPROCESSED] are executed.
 * - File changed: calculate hash and compare with hash stored in [CrateDefMap.fileInfos].
 *   Task [MACROS_WORKSPACE] is executed.
 * - File added: check whether [DefMapService.missedFiles] contains file path
 *   We schedule [MACROS_WORKSPACE] - see `ChangedMacroUpdater.handleEventWithoutFile`
 * - File deleted: check whether [DefMapService.fileIdToCrateId] contains this file
 *   We schedule [MACROS_WORKSPACE] - see `ChangedMacroUpdater.handleEventWithoutFile`
 * - Crate workspace changed: full recheck
 *   Tasks [CARGO_SYNC] and [MACROS_UNPROCESSED] are executed.
 */
fun DefMapService.getOrUpdateIfNeeded(crate: Crate): CrateDefMap? {
    check(project.isNewResolveEnabled)
    val holder = getDefMapHolder(crate.id ?: return null)

    if (holder.hasLatestStamp()) return holder.defMap

    return runReadAction {
        synchronized(defMapsBuildLock) {
            if (holder.hasLatestStamp()) return@synchronized holder.defMap

            val pool = Executors.newWorkStealingPool()
            val indicator = ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator()
            // TODO: Invoke outside of read action ?
            DefMapUpdater(this, pool, indicator, multithread = true).run()
            if (holder.defMap !== null) holder.checkHasLatestStamp()
            return@synchronized holder.defMap
        }
    }
}

/** Called from macro expansion task */
fun updateDefMapForAllCrates(project: Project, pool: Executor, indicator: ProgressIndicator, multithread: Boolean = true) {
    if (!project.isNewResolveEnabled) return
    val defMapService = project.defMapService
    runReadAction {
        synchronized(defMapService.defMapsBuildLock) {
            DefMapUpdater(defMapService, pool, indicator, multithread).run()
        }
    }
}

/** For tests */
fun Project.forceRebuildDefMapForAllCrates(multithread: Boolean) {
    val pool = Executors.newWorkStealingPool()
    val indicator = EmptyProgressIndicator()
    defMapService.scheduleRebuildAllDefMaps()
    updateDefMapForAllCrates(this, pool, indicator, multithread)
}

private class DefMapUpdater(
    private val defMapService: DefMapService,
    private val pool: Executor,
    private val indicator: ProgressIndicator,
    multithread: Boolean,
) {
    // Note: we can use only current thread if we are inside write action
    // (read action will not be started in other threads)
    private val multithread: Boolean = multithread && !isWriteAccessAllowed
    private val topSortedCrates: List<Crate> = defMapService.project.crateGraph.topSortedCrates

    fun run() {
        checkReadAccessAllowed()
        val time = measureTimeMillis {
            executeUnderProgress(indicator) {
                doRun()
            }
        }
        RESOLVE_LOG.info("Updated all DefMaps in $time ms")
    }

    private fun doRun() {
        check(defMapService.project.isNewResolveEnabled)
        if (topSortedCrates.isEmpty()) return
        indicator.checkCanceled()

        val cratesToCheck = findCratesToCheck()
        if (cratesToCheck.isEmpty()) return

        val cratesToUpdate = findCratesToUpdate(cratesToCheck)
        if (cratesToUpdate.isEmpty()) return

        defMapService.removeStaleDefMaps(topSortedCrates)

        val cratesToUpdateAll = cratesToUpdate.withReversedDependencies()
        val builtDefMaps = getBuiltDefMaps(cratesToUpdateAll)
        val pool = getPool(cratesToUpdateAll.size)
        val cratesToUpdateAllSorted = cratesToUpdateAll.topSort(topSortedCrates)
        DefMapMultithreadBuilder(defMapService, cratesToUpdateAllSorted, builtDefMaps, indicator, pool).build()
    }

    private fun findCratesToCheck(): List<Pair<Crate, DefMapHolder>> {
        checkReadAccessAllowed()
        val cratesToCheck = mutableListOf<Pair<Crate, DefMapHolder>>()
        for (crate in topSortedCrates) {
            val crateId = crate.id ?: continue
            val holder = defMapService.getDefMapHolder(crateId)
            if (holder.hasLatestStamp() || holder.definitelyShouldNotRebuild()) {
                holder.setLatestStamp()
            } else {
                cratesToCheck += Pair(crate, holder)
            }
        }
        return cratesToCheck
    }

    private fun findCratesToUpdate(cratesToCheck: List<Pair<Crate, DefMapHolder>>): List<Crate> {
        val pool = getPool(cratesToCheck.size)
        return cratesToCheck
            .filterAsync(pool) { (crate, holder) ->
                tryRunReadActionUnderIndicator(indicator) {
                    holder.updateShouldRebuild(crate)
                    val shouldRebuild = holder.shouldRebuild
                    if (!shouldRebuild) holder.setLatestStamp()
                    shouldRebuild
                }
            }
            .map { it.first }
    }

    private fun getBuiltDefMaps(cratesToUpdateAll: Set<Crate>): Map<Crate, CrateDefMap> {
        return topSortedCrates
            .filter { it !in cratesToUpdateAll }
            .mapNotNull {
                val crateId = it.id ?: return@mapNotNull null
                val defMap = defMapService.getDefMapHolder(crateId).defMap ?: return@mapNotNull null
                it to defMap
            }
            .toMap(THashMap())
    }

    private fun getPool(size: Int) = if (multithread && size > 1) pool else SingleThreadExecutor()
}

private fun List<Crate>.withReversedDependencies(): Set<Crate> {
    val result = hashSetOf<Crate>()
    fun processCrate(crate: Crate) {
        if (crate.id === null || !result.add(crate)) return
        for (reverseDependency in crate.reverseDependencies) {
            processCrate(reverseDependency)
        }
    }
    for (crate in this) {
        processCrate(crate)
    }
    return result
}

private fun Set<Crate>.topSort(topSortedCrates: List<Crate>): List<Crate> =
    topSortedCrates.filter { it in this }

class SingleThreadExecutor : Executor {
    override fun execute(action: Runnable) = action.run()
}

/** Does not persist order of elements */
private fun <T> Collection<T>.filterAsync(pool: Executor, predicate: (T) -> Boolean): List<T> {
    val result = ConcurrentLinkedQueue<T>()
    val future = CompletableFuture<Unit>()
    val remainingCount = AtomicInteger(size)

    for (element in this) {
        pool.execute {
            if (future.isCompletedExceptionally) return@execute
            try {
                if (predicate(element)) {
                    result += element
                }
                if (remainingCount.decrementAndGet() == 0) {
                    future.complete(Unit)
                }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
    }

    future.getWithRethrow()
    return result.toList()
}
