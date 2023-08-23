package org.opendc.simulator.compute.power;

import org.opendc.simulator.compute.SimMachine;

/**
 * A model for estimating part of the power usage of a {@link SimMachine} based on the network usage.
 */
public interface NetworkPowerModel {

    /**
     * Computes network power consumption for each host.
     *
     * @param utilization The bandwidth utilization percentage.
     * @return A double value of bandwidth power consumption (in W).
     */
    double computePower(double utilization);
}
