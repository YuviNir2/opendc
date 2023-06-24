/*
 * Copyright (c) 2022 AtLarge Research
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

package org.opendc.simulator.compute;

import java.time.InstantSource;
import org.jetbrains.annotations.NotNull;
import org.opendc.simulator.compute.model.NetworkAdapter;
import org.opendc.simulator.compute.model.ProcessingUnit;
import org.opendc.simulator.compute.power.CpuPowerModel;
import org.opendc.simulator.compute.power.NetworkPowerModel;
import org.opendc.simulator.compute.power.NetworkPowerModels;
import org.opendc.simulator.flow2.FlowGraph;
import org.opendc.simulator.flow2.FlowStage;
import org.opendc.simulator.flow2.FlowStageLogic;
import org.opendc.simulator.flow2.InHandler;
import org.opendc.simulator.flow2.InPort;
import org.opendc.simulator.flow2.OutPort;
import org.opendc.simulator.flow2.Outlet;

/**
 * A collection {@link SimPsu} implementations.
 */
public class SimPsuFactories {
    private SimPsuFactories() {}

    /**
     * Return a {@link SimPsuFactory} of {@link SimPsu} implementations that do not measure any power consumption.
     *
     * <p>
     * This implementation has the lowest performance impact and users are advised to use this factory if they do not
     * consider power consumption in their experiments.
     */
    public static SimPsuFactory noop() {
        return NoopPsu.FACTORY;
    }

    /**
     * Return a {@link SimPsuFactory} of {@link SimPsu} implementations that use a {@link CpuPowerModel} to estimate the
     * power consumption of a machine based on its CPU utilization.
     *
     * @param model The power model to estimate the power consumption based on the CPU usage.
     */
    public static SimPsuFactory simple(CpuPowerModel model) {
        return (machine, graph) -> new SimplePsu(graph, model);
    }

    public static SimPsuFactory simple(CpuPowerModel model, NetworkPowerModel networkModel) {
        return (machine, graph) -> new SimplePsu(graph, model, networkModel);
    }

    /**
     * A {@link SimPsu} implementation that does not attempt to measure power consumption.
     */
    private static final class NoopPsu extends SimPsu implements FlowStageLogic {
        private static final SimPsuFactory FACTORY = (machine, graph) -> new NoopPsu(graph);

        private final FlowStage stage;
        private final OutPort out;

        NoopPsu(FlowGraph graph) {
            stage = graph.newStage(this);
            out = stage.getOutlet("out");
            out.setMask(true);
        }

        @Override
        public double getPowerDemand() {
            return 0;
        }

        @Override
        public double getPowerUsage() {
            return 0;
        }

        @Override
        public double getEnergyUsage() {
            return 0;
        }

        @Override
        InPort getCpuPower(int id, ProcessingUnit model) {
            final InPort port = stage.getInlet("cpu" + id);
            port.setMask(true);
            return port;
        }

        @Override
        InPort getNicPower(int id, org.opendc.simulator.compute.model.NetworkAdapter model, InPort port) {
            final InPort newPort = stage.getInlet("eth" + id);
            port.setMask(true);
            return newPort;
        }

        @Override
        public long onUpdate(FlowStage ctx, long now) {
            return Long.MAX_VALUE;
        }

        @NotNull
        @Override
        public Outlet getFlowOutlet() {
            return out;
        }
    }

    /**
     * A {@link SimPsu} implementation that estimates the power consumption based on CPU usage.
     */
    private static final class SimplePsu extends SimPsu implements FlowStageLogic {
        private final FlowStage stage;
        private final OutPort out;
        private final CpuPowerModel model;
        private final NetworkPowerModel networkModel;
        private final InstantSource clock;

        private double maxBandwidth;
        private double totalBandwidth;

        private double targetFreq;
        private double totalUsage;
        private long lastUpdate;

        private double powerUsage;
        private double energyUsage;

        private final InHandler handler = new InHandler() {
            @Override
            public void onPush(InPort port, float demand) {
                totalUsage += -port.getDemand() + demand;
                System.out.println("SimPsuFactories handler Now=" + clock.millis() + " totalUsage="+ totalUsage + " demand=" + demand + " -port.getDemand()=" + -port.getDemand() + " port.name=" + port.getName()+ " port.capacity=" + port.getCapacity());
            }

            @Override
            public void onUpstreamFinish(InPort port, Throwable cause) {
                totalUsage -= port.getDemand();
            }
        };

        // TODO: totalBandwidth sometimes drops below minimum (0) because it is double and port.GetDemand & demand are float so I believe there's cutoff somewhere or something else is fucking it up
        private final InHandler ethHandler = new InHandler() {
            @Override
            public void onPush(InPort port, float demand) {
                totalBandwidth += -port.getDemand() + demand;
                System.out.println("SimPsuFactories ethhandler Now=" + clock.millis() + " totalBandwidth="+ totalBandwidth + " demand=" + demand + " -port.getDemand()=" + -port.getDemand()+ " port.name=" + port.getName() + " port.capacity=" + port.getCapacity());
            }

            @Override
            public void onUpstreamFinish(InPort port, Throwable cause) {
                totalBandwidth -= port.getDemand();
            }
        };

        SimplePsu(FlowGraph graph, CpuPowerModel model) {
            this.stage = graph.newStage(this);
            this.model = model;
            this.networkModel = NetworkPowerModels.linear(50, 10);
            this.clock = graph.getEngine().getClock();
            this.out = stage.getOutlet("out");
            this.out.setMask(true);

            lastUpdate = graph.getEngine().getClock().millis();
        }

        SimplePsu(FlowGraph graph, CpuPowerModel model, NetworkPowerModel networkModel) {
            this.stage = graph.newStage(this);
            this.model = model;
            this.networkModel = networkModel;
            this.clock = graph.getEngine().getClock();
            this.out = stage.getOutlet("out");
            this.out.setMask(true);

            lastUpdate = graph.getEngine().getClock().millis();
        }

        @Override
        public double getPowerDemand() {
            return totalUsage;
        }

        @Override
        public double getPowerUsage() {
            return powerUsage;
        }

        @Override
        public double getEnergyUsage() {
            updateEnergyUsage(clock.millis());
            return energyUsage;
        }

        @Override
        InPort getCpuPower(int id, ProcessingUnit model) {
            targetFreq += model.getFrequency();

            final InPort port = stage.getInlet("cpu" + id);
            port.setHandler(handler);
            return port;
        }

        @Override
        InPort getNicPower(int id, org.opendc.simulator.compute.model.NetworkAdapter model, InPort port) {
            maxBandwidth += model.getBandwidth();
//            System.out.println("getNicPower maxBandwidth=" + maxBandwidth + " model.getBandwidth()=" + model.getBandwidth());

//            final InPort port = stage.getInlet("eth" + id);
            port.setHandler(ethHandler);
            return port;
        }

        @Override
        void setCpuFrequency(InPort port, double capacity) {
            targetFreq += -port.getCapacity() + capacity;

            super.setCpuFrequency(port, capacity);
        }

        @Override
        public long onUpdate(FlowStage ctx, long now) {
            updateEnergyUsage(now);

            double usage = model.computePower(totalUsage / targetFreq);
            double networkUsage = networkModel.computePower(totalBandwidth / maxBandwidth);
            System.out.print("SimPsuFactories usage=" + usage + " totalUsage=" + totalUsage + " targetFreq=" + targetFreq);
            System.out.println(" networkUsage=" + networkUsage + " totalBandwidth=" + totalBandwidth + " maxBandwidth=" + maxBandwidth);
//            System.out.println("Pushing powerUsage=" + (usage) + " what's outPort? " + out.getName());
            out.push((float) usage);
            powerUsage = usage;

            return Long.MAX_VALUE;
        }

        @NotNull
        @Override
        public Outlet getFlowOutlet() {
            return out;
        }

        /**
         * Calculate the energy usage up until <code>now</code>.
         */
        private void updateEnergyUsage(long now) {
            long lastUpdate = this.lastUpdate;
            this.lastUpdate = now;

            long duration = now - lastUpdate;
            if (duration > 0) {
                // Compute the energy usage of the machine
                energyUsage += powerUsage * duration * 0.001;
            }
        }
    }
}
