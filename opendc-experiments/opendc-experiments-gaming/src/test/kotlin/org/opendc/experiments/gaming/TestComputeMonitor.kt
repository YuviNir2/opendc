package org.opendc.experiments.gaming

import org.opendc.experiments.compute.telemetry.ComputeMonitor
import org.opendc.experiments.compute.telemetry.table.HostTableReader
import org.opendc.experiments.compute.telemetry.table.ServiceTableReader

class TestComputeMonitor : ComputeMonitor {
    var attemptsSuccess = 0
    var attemptsFailure = 0
    var attemptsError = 0
    var serversPending = 0
    var serversActive = 0

    override fun record(reader: ServiceTableReader) {
        attemptsSuccess = reader.attemptsSuccess
        attemptsFailure = reader.attemptsFailure
        attemptsError = reader.attemptsError
        serversPending = reader.serversPending
        serversActive = reader.serversActive
    }

    var idleTime = 0L
    var activeTime = 0L
    var stealTime = 0L
    var lostTime = 0L
    var totalEnergyUsage = 0.0
    var uptime = 0L
    var downtime = 0L
    var cpuLimit = 0.0
    var cpuUsage = 0.0
    var cpuDemand = 0.0
    var cpuUtilization = 0.0
    var powerUsage = 0.0
    var nicUsage = 0.0
    var nicDemand = 0.0
    var nicLimit = 0.0
    var nicUtilization = 0.0

    override fun record(reader: HostTableReader) {
        idleTime += reader.cpuIdleTime
        activeTime += reader.cpuActiveTime
        stealTime += reader.cpuStealTime
        lostTime += reader.cpuLostTime
        totalEnergyUsage += reader.powerTotal
        uptime += reader.uptime
        downtime += reader.downtime
        cpuLimit += reader.cpuLimit
        cpuUsage += reader.cpuUsage
        cpuDemand += reader.cpuDemand
        cpuUtilization += reader.cpuUtilization
        powerUsage += reader.powerUsage
        nicUsage += reader.nicUsage
        nicDemand += reader.nicDemand
        nicLimit += reader.nicLimit
        nicUtilization += reader.nicUtilization
    }
}
