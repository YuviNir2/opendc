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

package org.opendc.simulator.compute.kernel;

import java.lang.reflect.Array;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.opendc.simulator.compute.SimAbstractMachine;
import org.opendc.simulator.compute.SimBareMetalMachine;
import org.opendc.simulator.compute.SimMachine;
import org.opendc.simulator.compute.SimMachineContext;
import org.opendc.simulator.compute.SimMemory;
import org.opendc.simulator.compute.SimNetworkInterface;
import org.opendc.simulator.compute.SimProcessingUnit;
import org.opendc.simulator.compute.SimStorageInterface;
import org.opendc.simulator.compute.device.SimPeripheral;
import org.opendc.simulator.compute.kernel.cpufreq.ScalingGovernor;
import org.opendc.simulator.compute.kernel.cpufreq.ScalingGovernorFactory;
import org.opendc.simulator.compute.kernel.cpufreq.ScalingPolicy;
import org.opendc.simulator.compute.kernel.interference.VmInterferenceDomain;
import org.opendc.simulator.compute.kernel.interference.VmInterferenceMember;
import org.opendc.simulator.compute.kernel.interference.VmInterferenceProfile;
import org.opendc.simulator.compute.model.MachineModel;
import org.opendc.simulator.compute.model.NetworkAdapter;
import org.opendc.simulator.compute.model.ProcessingUnit;
import org.opendc.simulator.compute.workload.SimWorkload;
import org.opendc.simulator.flow2.FlowGraph;
import org.opendc.simulator.flow2.FlowStage;
import org.opendc.simulator.flow2.FlowStageLogic;
import org.opendc.simulator.flow2.InHandler;
import org.opendc.simulator.flow2.InPort;
import org.opendc.simulator.flow2.Inlet;
import org.opendc.simulator.flow2.OutHandler;
import org.opendc.simulator.flow2.OutPort;
import org.opendc.simulator.flow2.Outlet;
import org.opendc.simulator.flow2.mux.FlowMultiplexer;
import org.opendc.simulator.flow2.mux.FlowMultiplexerFactory;

/**
 * A SimHypervisor facilitates the execution of multiple concurrent {@link SimWorkload}s, while acting as a single
 * workload to another {@link SimMachine}.
 */
public final class SimHypervisor implements SimWorkload {
    private final FlowMultiplexerFactory muxFactory;
    private final SplittableRandom random;
    private final ScalingGovernorFactory scalingGovernorFactory;
    private final VmInterferenceDomain interferenceDomain;

    private Context activeContext;
    private final ArrayList<VirtualMachine> vms = new ArrayList<>();
    private final HvCounters counters = new HvCounters();

    /**
     * Construct a {@link SimHypervisor} instance.
     *
     * @param muxFactory The factory for the {@link FlowMultiplexer} to multiplex the workloads.
     * @param random A randomness generator for the interference calculations.
     * @param scalingGovernorFactory The factory for the scaling governor to use for scaling the CPU frequency.
     * @param interferenceDomain The interference domain to which the hypervisor belongs.
     */
    private SimHypervisor(
            FlowMultiplexerFactory muxFactory,
            SplittableRandom random,
            ScalingGovernorFactory scalingGovernorFactory,
            VmInterferenceDomain interferenceDomain) {
        this.muxFactory = muxFactory;
        this.random = random;
        this.scalingGovernorFactory = scalingGovernorFactory;
        this.interferenceDomain = interferenceDomain;
    }

    /**
     * Create a {@link SimHypervisor} instance.
     *
     * @param muxFactory The factory for the {@link FlowMultiplexer} to multiplex the workloads.
     * @param random A randomness generator for the interference calculations.
     * @param scalingGovernorFactory The factory for the scaling governor to use for scaling the CPU frequency.
     * @param interferenceDomain The interference domain to which the hypervisor belongs.
     */
    public static SimHypervisor create(
            FlowMultiplexerFactory muxFactory,
            SplittableRandom random,
            ScalingGovernorFactory scalingGovernorFactory,
            VmInterferenceDomain interferenceDomain) {
        return new SimHypervisor(muxFactory, random, scalingGovernorFactory, interferenceDomain);
    }

    /**
     * Create a {@link SimHypervisor} instance with a default interference domain.
     *
     * @param muxFactory The factory for the {@link FlowMultiplexer} to multiplex the workloads.
     * @param random A randomness generator for the interference calculations.
     * @param scalingGovernorFactory The factory for the scaling governor to use for scaling the CPU frequency.
     */
    public static SimHypervisor create(
            FlowMultiplexerFactory muxFactory, SplittableRandom random, ScalingGovernorFactory scalingGovernorFactory) {
        return create(muxFactory, random, scalingGovernorFactory, new VmInterferenceDomain());
    }

    /**
     * Create a {@link SimHypervisor} instance with a default interference domain and scaling governor.
     *
     * @param muxFactory The factory for the {@link FlowMultiplexer} to multiplex the workloads.
     * @param random A randomness generator for the interference calculations.
     */
    public static SimHypervisor create(FlowMultiplexerFactory muxFactory, SplittableRandom random) {
        return create(muxFactory, random, null);
    }

    /**
     * Return the performance counters of the hypervisor.
     */
    public SimHypervisorCounters getCounters() {
        return counters;
    }

    /**
     * Return the virtual machines running on this hypervisor.
     */
    public List<? extends SimVirtualMachine> getVirtualMachines() {
        return Collections.unmodifiableList(vms);
    }

    /**
     * Create a {@link SimVirtualMachine} instance on which users may run a [SimWorkload].
     *
     * @param model The machine to create.
     */
    public SimVirtualMachine newMachine(MachineModel model) {
        if (!canFit(model)) {
            throw new IllegalArgumentException("Machine does not fit");
        }

        VirtualMachine vm = new VirtualMachine(model);
        vms.add(vm);
        return vm;
    }

    /**
     * Remove the specified <code>machine</code> from the hypervisor.
     *
     * @param machine The machine to remove.
     */
    public void removeMachine(SimVirtualMachine machine) {
        if (vms.remove(machine)) {
            // This cast must always succeed, since `_vms` only contains `VirtualMachine` types.
            ((VirtualMachine) machine).close();
        }
    }

    /**
     * Return the CPU capacity of the hypervisor in MHz.
     */
    public double getCpuCapacity() {
        final Context context = activeContext;

        if (context == null) {
            return 0.0;
        }

        return context.previousCpuCapacity;
    }

    /**
     * The CPU demand of the hypervisor in MHz.
     */
    public double getCpuDemand() {
        final Context context = activeContext;

        if (context == null) {
            return 0.0;
        }

        return context.previousCpuDemand;
    }

    public double getNetworkDemand() {
        final Context context = activeContext;

        if (context == null) {
            return 0.0;
        }

        return context.previousNetworkDemand;
    }

    /**
     * The CPU usage of the hypervisor in MHz.
     */
    public double getCpuUsage() {
        final Context context = activeContext;

        if (context == null) {
            return 0.0;
        }

        return context.previousCpuRate;
    }

    public double getNetworkUsage() {
        final Context context = activeContext;

        if (context == null) {
            return 0.0;
        }

        return context.previousNetworkRate;
    }

    /**
     * Determine whether the specified machine characterized by <code>model</code> can fit on this hypervisor at this
     * moment.
     */
    public boolean canFit(MachineModel model) {
        final Context context = activeContext;
        if (context == null) {
            return false;
        }

        // TODO: Add && that also checks that the NICs fit
        final FlowMultiplexer cpuMultiplexer = context.cpuMultiplexer;
        return (cpuMultiplexer.getMaxInputs() - cpuMultiplexer.getInputCount())
                >= model.getCpus().size();
    }

    @Override
    public void onStart(SimMachineContext ctx) {
        final Context context = new Context(ctx, muxFactory, scalingGovernorFactory, counters);
        context.start();
        activeContext = context;
    }

    @Override
    public void onStop(SimMachineContext ctx) {
        final Context context = activeContext;
        if (context != null) {
            activeContext = null;
            context.stop();
        }
    }

    @Override
    public SimWorkload snapshot() {
        throw new UnsupportedOperationException("Unable to snapshot hypervisor");
    }

    /**
     * The context which carries the state when the hypervisor is running on a machine.
     */
    private static final class Context implements FlowStageLogic {
        private final SimMachineContext ctx;
        private final FlowMultiplexer cpuMultiplexer;
        private final FlowMultiplexer networkMultiplexer;
        private final FlowStage stage;
        private final List<ScalingGovernor> scalingGovernors;
        private final InstantSource clock;
        private final HvCounters counters;

        private long lastCounterUpdate;
        private final double d;
        private float previousCpuDemand;
        private float previousCpuRate;
        private float previousCpuCapacity;
        private float previousNetworkRate;
        private float previousNetworkDemand;

        private Context(
                SimMachineContext ctx,
                FlowMultiplexerFactory muxFactory,
                ScalingGovernorFactory scalingGovernorFactory,
                HvCounters counters) {

            this.ctx = ctx;
            this.counters = counters;

            final FlowGraph graph = ctx.getGraph();
            this.cpuMultiplexer = muxFactory.newMultiplexer(graph);
            this.networkMultiplexer = muxFactory.newMultiplexer(graph);
            this.stage = graph.newStage(this);
            this.clock = graph.getEngine().getClock();

            this.lastCounterUpdate = clock.millis();

            if (scalingGovernorFactory != null) {
                this.scalingGovernors = ctx.getCpus().stream()
                        .map(cpu -> scalingGovernorFactory.newGovernor(new ScalingPolicyImpl(cpu)))
                        .collect(Collectors.toList());
            } else {
                this.scalingGovernors = Collections.emptyList();
            }

            float cpuCapacity = 0.f;
            final List<? extends SimProcessingUnit> cpus = ctx.getCpus();
            for (SimProcessingUnit cpu : cpus) {
                cpuCapacity += cpu.getFrequency();
            }
            this.d = cpus.size() / cpuCapacity;
        }

        /**
         * Start the hypervisor on a new machine.
         */
        void start() {
            final FlowGraph graph = ctx.getGraph();
            final FlowMultiplexer cpuMultiplexer = this.cpuMultiplexer;
            final FlowMultiplexer networkMultiplexer = this.networkMultiplexer;

            for (SimProcessingUnit cpu : ctx.getCpus()) {
                graph.connect(cpuMultiplexer.newOutput(), cpu.getInput());
            }

            for (SimNetworkInterface nic : ctx.getNetworkInterfaces()) {
                SimBareMetalMachine.Nic n = (SimBareMetalMachine.Nic)nic;
                graph.connect(networkMultiplexer.newOutput(), n.getInput());
            }

            for (ScalingGovernor governor : scalingGovernors) {
                governor.onStart();
            }
        }

        /**
         * Stop the hypervisor.
         */
        void stop() {
            // Synchronize the counters before stopping the hypervisor. Otherwise, the last report is missed.
            updateCounters(clock.millis());

            stage.close();
        }

        /**
         * Invalidate the {@link FlowStage} of the hypervisor.
         */
        void invalidate() {
            stage.invalidate();
        }

        /**
         * Update the performance counters of the hypervisor.
         *
         * @param now The timestamp at which to update the counter.
         */
        void updateCounters(long now) {
            long lastUpdate = this.lastCounterUpdate;
            this.lastCounterUpdate = now;
            long delta = now - lastUpdate;

            if (delta > 0) {
                final HvCounters counters = this.counters;

                float demand = previousCpuDemand;
                float rate = previousCpuRate;
                float capacity = previousCpuCapacity;

                final double factor = this.d * delta;
//                if (demand != 0 || rate != 0) System.out.println("SimHypervisor updateCounters now=" +now + " demand="+demand +" rate="+rate+ " capacity="+capacity +" factor="+factor);
                counters.cpuActiveTime += Math.round(rate * factor);
                counters.cpuIdleTime += Math.round((capacity - rate) * factor);
                counters.cpuStealTime += Math.round((demand - rate) * factor);
            }
        }

        /**
         * Update the performance counters of the hypervisor.
         */
        void updateCounters() {
            updateCounters(clock.millis());
        }

        @Override
        public long onUpdate(FlowStage ctx, long now) {
            updateCounters(now);

            final FlowMultiplexer cpuMultiplexer = this.cpuMultiplexer;
            final FlowMultiplexer networkMultiplexer = this.networkMultiplexer;
            final List<ScalingGovernor> scalingGovernors = this.scalingGovernors;

            float cpuDemand = cpuMultiplexer.getDemand();
            float cpuRate = cpuMultiplexer.getRate();
            float cpuCapacity = cpuMultiplexer.getCapacity();
            float networkRate = networkMultiplexer.getRate();
            float networkDemand = networkMultiplexer.getDemand();

            this.previousCpuDemand = cpuDemand;
            this.previousCpuRate = cpuRate;
            this.previousCpuCapacity = cpuCapacity;
            this.previousNetworkRate = networkRate;
            this.previousNetworkDemand = networkDemand;

            double load = cpuRate / Math.min(1.0, cpuCapacity);

            if (!scalingGovernors.isEmpty()) {
                for (ScalingGovernor governor : scalingGovernors) {
                    governor.onLimit(load);
                }
            }

            return Long.MAX_VALUE;
        }
    }

    /**
     * A {@link ScalingPolicy} for a physical CPU of the hypervisor.
     */
    private static final class ScalingPolicyImpl implements ScalingPolicy {
        private final SimProcessingUnit cpu;

        private ScalingPolicyImpl(SimProcessingUnit cpu) {
            this.cpu = cpu;
        }

        @Override
        public SimProcessingUnit getCpu() {
            return cpu;
        }

        @Override
        public double getTarget() {
            return cpu.getFrequency();
        }

        @Override
        public void setTarget(double target) {
            cpu.setFrequency(target);
        }

        @Override
        public double getMin() {
            return 0;
        }

        @Override
        public double getMax() {
            return cpu.getModel().getFrequency();
        }
    }

    /**
     * A virtual machine running on the hypervisor.
     */
    private class VirtualMachine extends SimAbstractMachine implements SimVirtualMachine {
        private boolean isClosed;
        private final VmCounters counters = new VmCounters(this);

        private VirtualMachine(MachineModel model) {
            super(model);
        }

        @Override
        public SimHypervisorCounters getCounters() {
            return counters;
        }

        @Override
        public double getCpuDemand() {
            final VmContext context = (VmContext) getActiveContext();

            if (context == null) {
                return 0.0;
            }

            return context.previousCpuDemand;
        }

        @Override
        public double getCpuUsage() {
            final VmContext context = (VmContext) getActiveContext();

            if (context == null) {
                return 0.0;
            }

            return context.cpuUsage;
        }

        @Override
        public double getCpuCapacity() {
            final VmContext context = (VmContext) getActiveContext();

            if (context == null) {
                return 0.0;
            }

            return context.previousCpuCapacity;
        }

        @Override
        public List<? extends SimPeripheral> getPeripherals() {
            return Collections.emptyList();
        }

        @Override
        protected Context createContext(
                SimWorkload workload, Map<String, Object> meta, Consumer<Exception> completion) {
            if (isClosed) {
                throw new IllegalStateException("Virtual machine does not exist anymore");
            }

            final SimHypervisor.Context context = activeContext;
            if (context == null) {
                throw new IllegalStateException("Hypervisor is inactive");
            }

            return new VmContext(
                    context,
                    this,
                    random,
                    interferenceDomain,
                    counters,
                    SimHypervisor.this.counters,
                    workload,
                    meta,
                    completion);
        }

        @Override
        public Context getActiveContext() {
            return super.getActiveContext();
        }

        void close() {
            if (isClosed) {
                return;
            }

            isClosed = true;
            cancel();
        }
    }

    /**
     * A {@link SimAbstractMachine.Context} for a virtual machine instance.
     */
    private static final class VmContext extends SimAbstractMachine.Context implements FlowStageLogic {
        private final Context context;
        private final SplittableRandom random;
        private final VmCounters vmCounters;
        private final HvCounters hvCounters;
        private final VmInterferenceMember interferenceMember;
        private final FlowStage stage;
        private final FlowMultiplexer cpuMultiplexer;
        private final FlowMultiplexer networkMultiplexer;
        private final InstantSource clock;

        private final List<VCpu> cpus;
        private final SimAbstractMachine.Memory memory;
        private final List<VNic> net;
        private final List<SimAbstractMachine.StorageDevice> disk;

        private final Inlet[] muxCpuInlets;
        private final Inlet[] muxNetworkInlets;
        private long lastUpdate;
        private long lastCounterUpdate;
        private final double d;

        private float cpuDemand;
        private float cpuUsage;
        private float cpuCapacity;

        private float networkDemand;
        private float networkUsage;
        private float previousNetworkDemand;

        private float previousCpuDemand;
        private float previousCpuCapacity;

        private VmContext(
                Context context,
                VirtualMachine machine,
                SplittableRandom random,
                VmInterferenceDomain interferenceDomain,
                VmCounters vmCounters,
                HvCounters hvCounters,
                SimWorkload workload,
                Map<String, Object> meta,
                Consumer<Exception> completion) {
            super(machine, workload, meta, completion);

            this.context = context;
            this.random = random;
            this.vmCounters = vmCounters;
            this.hvCounters = hvCounters;
            this.clock = context.clock;

            final VmInterferenceProfile interferenceProfile = (VmInterferenceProfile) meta.get("interference-profile");
            VmInterferenceMember interferenceMember = null;
            if (interferenceDomain != null && interferenceProfile != null) {
                interferenceMember = interferenceDomain.join(interferenceProfile);
                interferenceMember.activate();
            }
            this.interferenceMember = interferenceMember;

            final FlowGraph graph = context.ctx.getGraph();
            final FlowStage stage = graph.newStage(this);
            this.stage = stage;
            this.lastUpdate = clock.millis();
            this.lastCounterUpdate = clock.millis();

            final FlowMultiplexer cpuMultiplexer = context.cpuMultiplexer;
            this.cpuMultiplexer = cpuMultiplexer;
            final FlowMultiplexer networkMultiplexer = context.networkMultiplexer;
            this.networkMultiplexer = networkMultiplexer;

            final MachineModel model = machine.getModel();
            final List<ProcessingUnit> cpuModels = model.getCpus();
            final List<NetworkAdapter> networkModels = model.getNetwork();
//            final Inlet[] muxInlets = new Inlet[cpuModels.size()];
            final Inlet[] muxCpuInlets = new Inlet[cpuModels.size()];
            final Inlet[] muxNetworkInlets = new Inlet[networkModels.size()];

            final ArrayList<VCpu> cpus = new ArrayList<>();

            this.muxCpuInlets = muxCpuInlets;
            this.muxNetworkInlets = muxNetworkInlets;
            this.cpus = cpus;

            float capacity = 0.f;

            for (int i = 0; i < cpuModels.size(); i++) {
                final Inlet muxInlet = cpuMultiplexer.newInput();
                muxCpuInlets[i] = muxInlet;

                final InPort input = stage.getInlet("vcpu" + i);
                final OutPort output = stage.getOutlet("vcpumux" + i);

                final Handler handler = new Handler(this, input, output);
                input.setHandler(handler);
                output.setHandler(handler);

                final ProcessingUnit cpuModel = cpuModels.get(i);
                capacity += cpuModel.getFrequency();

                final VCpu cpu = new VCpu(cpuModel, input);
                cpus.add(cpu);

                graph.connect(output, muxInlet);
            }
            this.d = cpuModels.size() / capacity;

            this.memory = new SimAbstractMachine.Memory(graph, model.getMemory());

            final ArrayList<VNic> net = new ArrayList<>();
            this.net = net;
            for (int i = 0; i < networkModels.size(); i++) {
                final Inlet muxInlet = networkMultiplexer.newInput();
                muxNetworkInlets[i] = muxInlet;
                final InPort input = stage.getInlet("vnic" + (i));
                final OutPort output = stage.getOutlet("ethvmux" + i);
                final Handler handler = new Handler(this, input, output);
                input.setHandler(handler);
                output.setHandler(handler);
                final NetworkAdapter netModel = networkModels.get(i);
//                final SimAbstractMachine.NetworkAdapter netAdapter = new SimAbstractMachine.NetworkAdapter(graph, netModel, i-cpuModels.size());
                final VNic nic = new VNic(graph, netModel, i, input);
                net.add(nic);
                input.pull((float) netModel.getBandwidth());

                graph.connect(output, muxInlet);
            }
//            int netIndex = 0;
//            for (org.opendc.simulator.compute.model.NetworkAdapter adapter : model.getNetwork()) {
//                net.add(new SimAbstractMachine.NetworkAdapter(graph, adapter, netIndex++));
//            }

            int diskIndex = 0;
            final ArrayList<SimAbstractMachine.StorageDevice> disk = new ArrayList<>();
            this.disk = disk;
            for (org.opendc.simulator.compute.model.StorageDevice device : model.getStorage()) {
                disk.add(new SimAbstractMachine.StorageDevice(graph, device, diskIndex++));
            }
        }

        /**
         * Update the performance counters of the virtual machine.
         *
         * @param now The timestamp at which to update the counter.
         */
        void updateCounters(long now) {
            long lastUpdate = this.lastCounterUpdate;
            this.lastCounterUpdate = now;
            long delta = now - lastUpdate;

            if (delta > 0) {
                final VmCounters counters = this.vmCounters;

                float demand = this.previousCpuDemand;
                float rate = this.cpuUsage;
                float capacity = this.previousCpuCapacity;

                final double factor = this.d * delta;
                final double active = rate * factor;

                counters.cpuActiveTime += Math.round(active);
                counters.cpuIdleTime += Math.round((capacity - rate) * factor);
                counters.cpuStealTime += Math.round((demand - rate) * factor);
            }
        }

        /**
         * Update the performance counters of the virtual machine.
         */
        void updateCounters() {
            updateCounters(clock.millis());
        }

        @Override
        public FlowGraph getGraph() {
            return stage.getGraph();
        }

        @Override
        public List<? extends SimProcessingUnit> getCpus() {
            return cpus;
        }

        @Override
        public SimMemory getMemory() {
            return memory;
        }

        @Override
        public List<? extends SimNetworkInterface> getNetworkInterfaces() {
            return net;
        }

        @Override
        public List<? extends SimStorageInterface> getStorageInterfaces() {
            return disk;
        }

        @Override
        public long onUpdate(FlowStage ctx, long now) {
            float cpuUsage = 0.f;
            float networkUsage = 0.f;
            for (Inlet inlet : muxCpuInlets) {
                cpuUsage += ((InPort) inlet).getRate();
            }
            for (Inlet inlet : muxNetworkInlets) {
                networkUsage += ((InPort) inlet).getRate();
            }
            this.cpuUsage = cpuUsage;
            this.networkUsage = networkUsage;
            this.previousCpuDemand = cpuDemand;
            this.previousNetworkDemand = networkDemand;
            this.previousCpuCapacity = cpuCapacity;

            long lastUpdate = this.lastUpdate;
            this.lastUpdate = now;
            long delta = now - lastUpdate;

            if (delta > 0) {
                final VmInterferenceMember interferenceMember = this.interferenceMember;
                double penalty = 0.0;

                if (interferenceMember != null) {
                    final FlowMultiplexer multiplexer = this.cpuMultiplexer;
                    double load = multiplexer.getRate() / Math.min(1.0, multiplexer.getCapacity());
                    penalty = 1 - interferenceMember.apply(random, load);
                }

                final double factor = this.d * delta;
                final long lostTime = Math.round(factor * cpuUsage * penalty);

                this.vmCounters.cpuLostTime += lostTime;
                this.hvCounters.cpuLostTime += lostTime;
            }

            // Invalidate the FlowStage of the hypervisor to update its counters (via onUpdate)
            context.invalidate();

            return Long.MAX_VALUE;
        }

        @Override
        protected void doCancel() {
            super.doCancel();

            // Synchronize the counters before stopping the hypervisor. Otherwise, the last report is missed.
            updateCounters(clock.millis());

            stage.close();

            final FlowMultiplexer cpuMultiplexer = this.cpuMultiplexer;
            final FlowMultiplexer networkMultiplexer = this.networkMultiplexer;
            for (Inlet muxInlet : muxCpuInlets) {
                cpuMultiplexer.releaseInput(muxInlet);
            }

            for (Inlet muxInlet : muxNetworkInlets) {
                networkMultiplexer.releaseInput(muxInlet);
            }

            final VmInterferenceMember interferenceMember = this.interferenceMember;
            if (interferenceMember != null) {
                interferenceMember.deactivate();
            }
        }
    }

    /**
     * A {@link SimProcessingUnit} of a virtual machine.
     */
    private static final class VCpu implements SimProcessingUnit {
        private final ProcessingUnit model;
        private final InPort input;

        private VCpu(ProcessingUnit model, InPort input) {
            this.model = model;
            this.input = input;

            input.pull((float) model.getFrequency());
        }

        @Override
        public double getFrequency() {
            return input.getCapacity();
        }

        @Override
        public void setFrequency(double frequency) {
            input.pull((float) frequency);
        }

        @Override
        public double getDemand() {
            return input.getDemand();
        }

        @Override
        public double getSpeed() {
            return input.getRate();
        }

        @Override
        public ProcessingUnit getModel() {
            return model;
        }

        @Override
        public Inlet getInput() {
            return input;
        }

        @Override
        public String toString() {
            return "SimHypervisor.VCpu[model" + model + "]";
        }
    }

    public static final class VNic extends SimAbstractMachine.NetworkAdapter {

        private final InPort input;
//        private final OutPort output;

        private VNic(FlowGraph graph, org.opendc.simulator.compute.model.NetworkAdapter model, int index, InPort input) {
            super(graph, model, index);
            this.input = input;

            this.input.pull((float) model.getBandwidth());
        }

        public InPort getInput() {
            return input;
        }
    }

    /**
     * A handler for forwarding flow between an inlet and outlet.
     */
    private static class Handler implements InHandler, OutHandler {
        private final InPort input;
        private final OutPort output;
        private final VmContext context;

        private Handler(VmContext context, InPort input, OutPort output) {
            this.context = context;
            this.input = input;
            this.output = output;
        }

        @Override
        public void onPush(InPort port, float demand) {
            if (port.getName().contains("eth") || port.getName().contains("nic")) {
//                System.out.println("SimHypervisor Handler onPush NIC InPort:" + port.getName() + " port.handler():" + port.getHandler() + " outport:" + output.getName() + " demand:" + demand);
                context.networkDemand += -port.getDemand() + demand;
            } else {
//                System.out.println("SimHypervisor Handler onPush CPU InPort:" + port.getName() + " port.handler():" + port.getHandler() + " outport:" + output.getName() + " demand:" + demand);
                context.cpuDemand += -port.getDemand() + demand;
            }

            output.push(demand);
        }

        @Override
        public void onUpstreamFinish(InPort port, Throwable cause) {
//            System.out.println("SimHypervisor Handler onUpstreamFinish InPort:" + port.getName() + " outport:" + output.getName());
            context.cpuDemand -= port.getDemand();

            output.push(0.f);
        }

        @Override
        public float getRate(InPort port) {
            return output.getRate();
        }

        @Override
        public void onPull(OutPort port, float capacity) {
//            System.out.println("SimHypervisor Handler onPull OutPort:" + port.getName() + " outport:" + input.getName());
            context.cpuCapacity += -port.getCapacity() + capacity;

            input.pull(capacity);
        }

        @Override
        public void onDownstreamFinish(OutPort port, Throwable cause) {
//            System.out.println("SimHypervisor Handler onDownstreamFinish OutPort:" + port.getName() + " outport:" + input.getName());
            context.cpuCapacity -= port.getCapacity();

            input.pull(0.f);
        }
    }

    /**
     * Implementation of {@link SimHypervisorCounters} for the hypervisor.
     */
    private class HvCounters implements SimHypervisorCounters {
        private long cpuActiveTime;
        private long cpuIdleTime;
        private long cpuStealTime;
        private long cpuLostTime;

        @Override
        public long getCpuActiveTime() {
            return cpuActiveTime;
        }

        @Override
        public long getCpuIdleTime() {
            return cpuIdleTime;
        }

        @Override
        public long getCpuStealTime() {
            return cpuStealTime;
        }

        @Override
        public long getCpuLostTime() {
            return cpuLostTime;
        }

        @Override
        public void sync() {
            final Context context = activeContext;

            if (context != null) {
                context.updateCounters();
            }
        }
    }

    /**
     * Implementation of {@link SimHypervisorCounters} for the virtual machine.
     */
    private static class VmCounters implements SimHypervisorCounters {
        private final VirtualMachine vm;
        private long cpuActiveTime;
        private long cpuIdleTime;
        private long cpuStealTime;
        private long cpuLostTime;

        private VmCounters(VirtualMachine vm) {
            this.vm = vm;
        }

        @Override
        public long getCpuActiveTime() {
            return cpuActiveTime;
        }

        @Override
        public long getCpuIdleTime() {
            return cpuIdleTime;
        }

        @Override
        public long getCpuStealTime() {
            return cpuStealTime;
        }

        @Override
        public long getCpuLostTime() {
            return cpuLostTime;
        }

        @Override
        public void sync() {
            final VmContext context = (VmContext) vm.getActiveContext();

            if (context != null) {
                context.updateCounters();
            }
        }
    }
}
