/*
 * Copyright (c) 2021 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

@file:JvmName("TopologyFactories")

package org.opendc.experiments.rsuv.topology

import org.opendc.experiments.compute.topology.HostSpec
import org.opendc.simulator.compute.SimPsuFactories
import org.opendc.simulator.compute.model.MachineModel
import org.opendc.simulator.compute.model.MemoryUnit
import org.opendc.simulator.compute.model.NetworkAdapter
import org.opendc.simulator.compute.model.ProcessingNode
import org.opendc.simulator.compute.model.ProcessingUnit
import org.opendc.simulator.compute.power.CpuPowerModel
import org.opendc.simulator.compute.power.CpuPowerModels
import org.opendc.simulator.compute.power.NetworkPowerModel
import org.opendc.simulator.compute.power.NetworkPowerModels
import java.io.File
import java.io.InputStream
import java.util.SplittableRandom
import java.util.UUID
import java.util.random.RandomGenerator
import kotlin.math.roundToLong

/**
 * A [ClusterSpecReader] that is used to read the cluster definition file.
 */
private val reader = ClusterSpecReader()

/**
 * Construct a topology from the specified [file].
 */
fun clusterTopology(
    file: File,
    powerModel: CpuPowerModel = CpuPowerModels.linear(350.0, 200.0),
    networkModel: NetworkPowerModel = NetworkPowerModels.linear(50.0, 10.0),
    random: RandomGenerator = SplittableRandom(0)
): List<HostSpec> {
    return clusterTopology(reader.read(file), powerModel, networkModel, random)
}

/**
 * Construct a topology from the specified [input].
 */
fun clusterTopology(
    input: InputStream,
    powerModel: CpuPowerModel = CpuPowerModels.linear(350.0, 200.0),
    networkModel: NetworkPowerModel = NetworkPowerModels.linear(50.0, 10.0),
    random: RandomGenerator = SplittableRandom(0)
): List<HostSpec> {
    return clusterTopology(reader.read(input), powerModel, networkModel, random)
}

/**
 * Construct a topology from the given list of [clusters].
 */
fun clusterTopology(clusters: List<ClusterSpec>, powerModel: CpuPowerModel, networkModel: NetworkPowerModel, random: RandomGenerator = SplittableRandom(0)): List<HostSpec> {
    return clusters.flatMap { it.toHostSpecs(random, powerModel, networkModel) }
}

/**
 * Helper method to convert a [ClusterSpec] into a list of [HostSpec]s.
 */
private fun ClusterSpec.toHostSpecs(random: RandomGenerator, powerModel: CpuPowerModel, networkModel: NetworkPowerModel): List<HostSpec> {
    val cpuSpeed = cpuSpeed
    val cpuCountPerHost = cpuCount/hostCount
    val memoryPerHost = (memCapacity/hostCount).roundToLong()

    val unknownProcessingNode = ProcessingNode("unknown", "unknown", "unknown", cpuCountPerHost)
    val unknownMemoryUnit = MemoryUnit("unknown", "unknown", -1.0, memoryPerHost)
    // TODO: make safe in case Nic not provided
    val unknownNetworkUnit = NetworkAdapter("unknown", "unknown", bandWidthPerNic)
    val machineModel = MachineModel(
        List(cpuCountPerHost) { coreId -> ProcessingUnit(unknownProcessingNode, coreId, cpuSpeed) },
        listOf(unknownMemoryUnit),
        List(nicCountPerHost) { _ -> unknownNetworkUnit}
    )

    return List(hostCount) {
        HostSpec(
            UUID(random.nextLong(), it.toLong()),
            "node-$name-$it",
            mapOf("cluster" to id),
            machineModel,
            SimPsuFactories.simple(powerModel, networkModel)
        )
    }
}
