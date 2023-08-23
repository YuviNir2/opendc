package org.opendc.experiments.rsuv

import org.opendc.simulator.compute.workload.SimTrace
import kotlin.math.max

class FragmentBuilder {
    /**
     * The total load of the trace.
     */
    @JvmField
    var totalLoad: Double = 0.0

    /**
     * The internal builder for the trace.
     */
    private val builder = SimTrace.builder()

    /**
     * The deadline of the previous fragment.
     */
    private var previousDeadline = Long.MIN_VALUE

    /**
     * Add a fragment to the trace.
     *
     * @param timestamp Timestamp at which the fragment starts (in epoch millis).
     * @param deadline Timestamp at which the fragment ends (in epoch millis).
     * @param usage CPU usage of this fragment.
     * @param cores Number of cores used.
     * @param networkUsage network usage of this fragment
     */


    fun add(timestamp: Long, deadline: Long, usage: Double, cores: Int, networkUsage: Double) {
        val duration = max(0, deadline - timestamp)
        totalLoad += (usage * duration) / 1000.0 // avg MHz * duration = MFLOPs

        if (timestamp != previousDeadline) {
            // There is a gap between the previous and current fragment; fill the gap
            builder.add(timestamp, 0.0, cores, 0.0)
        }

        builder.add(deadline, usage, cores, networkUsage)
        previousDeadline = deadline
    }

    /**
     * Build the trace.
     */
    fun build(): SimTrace = builder.build()
}
