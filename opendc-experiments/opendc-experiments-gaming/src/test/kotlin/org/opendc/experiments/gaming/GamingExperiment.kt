/*
 * Copyright (c) 2020 AtLarge Research
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

package org.opendc.experiments.gaming

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.filters.RamFilter
import org.opendc.compute.service.scheduler.filters.VCpuFilter
import org.opendc.compute.service.scheduler.weights.CoreRamWeigher
 import org.opendc.experiments.compute.ExtendedVirtualMachine
 import org.opendc.experiments.gaming.topology.clusterTopology
 import org.opendc.experiments.compute.registerComputeMonitor
 import org.opendc.experiments.compute.replayExtended
 import org.opendc.experiments.compute.setupComputeService
import org.opendc.experiments.compute.setupHosts
import org.opendc.experiments.compute.topology.HostSpec
import org.opendc.experiments.provisioner.Provisioner
import org.opendc.simulator.compute.power.CpuPowerModel
import org.opendc.simulator.compute.power.NetworkPowerModel
import org.opendc.simulator.kotlin.runSimulation
import kotlin.random.Random

/**
 * An integration test suite for my experiments.
 */
class GamingExperiment {
    /**
     * The monitor used to keep track of the metrics.
     */
    private lateinit var monitor: TestComputeMonitor

    /**
     * The [FilterScheduler] to use for all experiments.
     */
    private lateinit var computeScheduler: FilterScheduler

    /**
     * Set up the experimental environment.
     */
    @BeforeEach
    fun setUp() {
        monitor = TestComputeMonitor()
        computeScheduler = FilterScheduler(
            filters = listOf(ComputeFilter(), VCpuFilter(3.0), RamFilter(1.0)),
            weighers = listOf(CoreRamWeigher(multiplier = 1.0))
        )
    }

    /**
     * Test a small simulation setup.
     */
    @Test
    fun runExperiment() = runSimulation {
        val seed = 1L
        val configName = "gamegenre_fps"
        getExperimentConfiguration(configName)
        val topology = createTopology(envFileName, getCpuPowerModel(cpuPowerModel, cpuMaxPower, cpuIdlePower), getNetworkPowerModel(nicPowerModel, nicMaxPower, nicIdlePower))
        val workload = getWorkload(traceFileName)
        val monitor = monitor

        Provisioner(dispatcher, seed).use { provisioner ->
            provisioner.runSteps(
                setupComputeService(serviceDomain = "compute.opendc.org", { computeScheduler }),
                registerComputeMonitor(serviceDomain = "compute.opendc.org", monitor),
                setupHosts(serviceDomain = "compute.opendc.org", topology)
            )

            val service = provisioner.registry.resolve("compute.opendc.org", ComputeService::class.java)!!
            service.replayExtended(timeSource, workload, seed)
        }

        val resultFileName = "env-${envFileName}_trace-${traceFileName}_config-${configName}_${Random.nextInt(1000)}"
        val file = baseDir.resolve("results/${resultFileName}.txt")
        file.createNewFile()
        file.bufferedWriter().use { writer ->
            writer.write("Number of VMs successfully deployed;idleTime;activeTime;stealTime;lostTime;cpuDemand;cpuLimit;energyUsage(Total Energy Consumption);powerUsage;cpuUtilization\n")
            writer.write("${monitor.attemptsSuccess};${monitor.idleTime};${monitor.activeTime};${monitor.stealTime};${monitor.lostTime};${monitor.cpuDemand};${monitor.cpuLimit};${monitor.totalEnergyUsage};${monitor.powerUsage};${monitor.cpuUtilization}\n")
        }

        println("Result file name: $resultFileName")
        println(
            "\nScheduler: \n" +
                "Number of VMs successfully deployed=${monitor.attemptsSuccess}\n" +
                "Failure=${monitor.attemptsFailure}\n" +
                "Error=${monitor.attemptsError}\n" +
                "Pending=${monitor.serversPending}\n" +
                "Active=${monitor.serversActive}\n" +
                "idleTime=${monitor.idleTime}\n" +
                "activeTime=${monitor.activeTime}\n" +
                "stealTime=${monitor.stealTime}\n" +
                "lostTime=${monitor.lostTime}\n" +
                "cpuDemand=${monitor.cpuDemand}\n" +
                "cpuLimit=${monitor.cpuLimit}\n" +
                "energyUsage(Total Energy Consumption)=${monitor.totalEnergyUsage}\n" +
                "powerUsage=${monitor.powerUsage}\n" +
                "cpuUtilization=${monitor.cpuUtilization}\n" +
                "nicUsage=${monitor.nicUsage}\n" +
                "nicUtilization=${monitor.nicUtilization}\n" +
                "nicDemand=${monitor.nicDemand}\n" +
                "nicLimit=${monitor.nicLimit}\n" +
                "uptime=${monitor.uptime} \n" +
                "downtime=${monitor.downtime} \n"
        )
    }

    private fun getWorkload(traceName: String): List<ExtendedVirtualMachine> {
        val traceFile = baseDir.resolve("traces/${traceName}.csv")
        val fragments = buildFragments(traceFile, maxNumPlayersPerVm, gameType, dataRateLevel)
        return generateWorkload(fragments, maxNumPlayersPerVm, gameType, dataRateLevel)
    }

    /**
     * Obtain the topology factory for the test.
     */
    private fun createTopology(name: String, cpuPowerModel: CpuPowerModel, networkPowerModel: NetworkPowerModel): List<HostSpec> {
        val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/env/$name.txt"))
        return stream.use {
            clusterTopology(
                stream,
                cpuPowerModel,
                networkPowerModel
            )
        }
    }
}
