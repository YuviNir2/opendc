package org.opendc.simulator.compute.power;

import org.opendc.simulator.compute.model.NetworkAdapter;

/**
 * A collection {@link NetworkPowerModel} implementations.
 */
public class NetworkPowerModels {
    private NetworkPowerModels() {}

    public static NetworkPowerModel constant(double power) {
        return new ConstantPowerModel(power);
    }

    /**
     * Construct a square root {@link NetworkPowerModel} that is adapted from CloudSim.
     *
     * @param maxPower The maximum power draw of the server in W.
     * @param idlePower The power draw of the server at its lowest utilization level in W.
     */
    public static NetworkPowerModel sqrt(double maxPower, double idlePower) {
        return new SqrtPowerModel(maxPower, idlePower);
    }

    public static NetworkPowerModel linear(double maxPower, double idlePower) {
        return new LinearPowerModel(maxPower, idlePower);
    }

    public static NetworkPowerModel square(double maxPower, double idlePower) {
        return new SquarePowerModel(maxPower, idlePower);
    }

    public static NetworkPowerModel cubic(double maxPower, double idlePower) {
        return new CubicPowerModel(maxPower, idlePower);
    }

    public static NetworkPowerModel zeroIdle(NetworkPowerModel delegate) {
        return new NetworkPowerModels.ZeroIdlePowerDecorator(delegate);
    }

    private static final class ConstantPowerModel implements NetworkPowerModel {
        private final double power;

        ConstantPowerModel(double power) {
            this.power = power;
        }

        @Override
        public double computePower(double utilization) {
            return power;
        }

        @Override
        public String toString() {
            return "ConstantPowerModel[power=" + power + "]";
        }
    }

    private abstract static class MaxIdlePowerModel implements NetworkPowerModel {
        protected final double maxPower;
        protected final double idlePower;

        MaxIdlePowerModel(double maxPower, double idlePower) {
            this.maxPower = maxPower;
            this.idlePower = idlePower;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[max=" + maxPower + ",idle=" + idlePower + "]";
        }
    }

    private static final class SqrtPowerModel extends MaxIdlePowerModel {
        private final double factor;

        SqrtPowerModel(double maxPower, double idlePower) {
            super(maxPower, idlePower);
            this.factor = (maxPower - idlePower) / Math.sqrt(100);
        }

        @Override
        public double computePower(double utilization) {
            return idlePower + factor * Math.sqrt(utilization * 100);
        }
    }

    private static final class LinearPowerModel extends MaxIdlePowerModel {
        private final double factor;

        LinearPowerModel(double maxPower, double idlePower) {
            super(maxPower, idlePower);
            this.factor = (maxPower - idlePower) / 100;
        }

        @Override
        public double computePower(double utilization) {
            return idlePower + factor * utilization * 100;
        }
    }

    private static final class SquarePowerModel extends MaxIdlePowerModel {
        private final double factor;

        SquarePowerModel(double maxPower, double idlePower) {
            super(maxPower, idlePower);
            this.factor = (maxPower - idlePower) / Math.pow(100, 2);
        }

        @Override
        public double computePower(double utilization) {
            return idlePower + factor * Math.pow(utilization * 100, 2);
        }
    }

    private static final class CubicPowerModel extends MaxIdlePowerModel {
        private final double factor;

        CubicPowerModel(double maxPower, double idlePower) {
            super(maxPower, idlePower);
            this.factor = (maxPower - idlePower) / Math.pow(100, 3);
        }

        @Override
        public double computePower(double utilization) {
            return idlePower + factor * Math.pow(utilization * 100, 3);
        }
    }

    private static final class ZeroIdlePowerDecorator implements NetworkPowerModel {
        private final NetworkPowerModel delegate;

        ZeroIdlePowerDecorator(NetworkPowerModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public double computePower(double utilization) {
            if (utilization == 0.0) {
                return 0.0;
            }

            return delegate.computePower(utilization);
        }

        @Override
        public String toString() {
            return "ZeroIdlePowerDecorator[delegate=" + delegate + "]";
        }
    }
}
