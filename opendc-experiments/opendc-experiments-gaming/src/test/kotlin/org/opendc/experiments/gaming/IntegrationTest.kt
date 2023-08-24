package org.opendc.experiments.gaming

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.filters.RamFilter
import org.opendc.compute.service.scheduler.filters.VCpuFilter
import org.opendc.compute.service.scheduler.weights.CoreRamWeigher
import org.opendc.experiments.compute.ExtendedVirtualMachine
import org.opendc.experiments.compute.registerComputeMonitor
import org.opendc.experiments.compute.replayExtended
import org.opendc.experiments.compute.setupComputeService
import org.opendc.experiments.compute.setupHosts
import org.opendc.experiments.compute.topology.HostSpec
import org.opendc.experiments.gaming.topology.clusterTopology
import org.opendc.experiments.provisioner.Provisioner
import org.opendc.simulator.compute.power.CpuPowerModel
import org.opendc.simulator.compute.power.NetworkPowerModel
import org.opendc.simulator.kotlin.runSimulation

class IntegrationTest {

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

    @Test
    fun integrationTest() = runSimulation {
        val seed = 1L
        getExperimentConfiguration("base_config")
        val topology = createTopology(getCpuPowerModel(cpuPowerModel, cpuMaxPower, cpuIdlePower), getNetworkPowerModel(nicPowerModel, nicMaxPower, nicIdlePower))
        val workload = getWorkload()
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

        // Note that these values have been verified beforehand
        assertAll(
            { Assertions.assertEquals(true, monitor.attemptsSuccess > 4, "There should be at least 5 VMs scheduled") },
            { Assertions.assertEquals(0, monitor.serversActive, "All VMs should finish after a run") },
            { Assertions.assertEquals(0, monitor.attemptsFailure, "No VM should be unscheduled") },
            { Assertions.assertEquals(0, monitor.serversPending, "No VM should not be in the queue") },
            { Assertions.assertEquals(1.343328E7, monitor.cpuLimit) { "Incorrect CPU limit" } },
            { Assertions.assertEquals(8.61E7, monitor.nicLimit) { "Incorrect NIC limit" } },
            { Assertions.assertEquals(113400000 , monitor.uptime) { "Incorrect uptime" } },
        )
    }


    private fun getWorkload(): List<ExtendedVirtualMachine> {
        val traceFile = baseDir.resolve("traces/simple_trace.csv")
        val fragments = buildFragments(traceFile, maxNumPlayersPerVm, gameType, dataRateLevel)
        return generateWorkload(fragments, maxNumPlayersPerVm, gameType, dataRateLevel)
    }

    /**
     * Obtain the topology factory for the test.
     */
    private fun createTopology(cpuPowerModel: CpuPowerModel, networkPowerModel: NetworkPowerModel): List<HostSpec> {
        val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/env/topology.txt"))
        return stream.use {
            clusterTopology(
                stream,
                cpuPowerModel,
                networkPowerModel
            )
        }
    }
}
