package com.acltabontabon.vortex.core.environment;

/**
 * What an environment actually provides.
 *
 * <p>Declared rather than inferred. Vortex never assumes that "staging" means production-like,
 * because in most organisations it does not.
 *
 * @param usesMockDependencies         downstream systems are simulated
 * @param usesLocalStack               cloud services are emulated locally
 * @param productionLikeInfrastructure the environment is sized and configured like production
 * @param sharedEnvironment            other people depend on this environment being usable
 * @param distributedExecutionAllowed  load may be generated from more than one machine
 */
public record EnvironmentCapabilities(
        boolean usesMockDependencies,
        boolean usesLocalStack,
        boolean productionLikeInfrastructure,
        boolean sharedEnvironment,
        boolean distributedExecutionAllowed) {

    public static EnvironmentCapabilities localIsolated() {
        return new EnvironmentCapabilities(true, false, false, false, false);
    }

    public static EnvironmentCapabilities none() {
        return new EnvironmentCapabilities(false, false, false, false, false);
    }

    /**
     * The class of question this environment can answer.
     *
     * <p>An environment answers integrated questions only when its dependencies are real
     * <em>and</em> its infrastructure resembles production. Anything else is isolated, however
     * remote the machine happens to be.
     */
    public TestClassification classify(DependencyMode dependencyMode) {
        boolean realDependencies = dependencyMode == DependencyMode.REAL;
        boolean simulated = usesMockDependencies || usesLocalStack;
        return realDependencies && productionLikeInfrastructure && !simulated
                ? TestClassification.INTEGRATED
                : TestClassification.ISOLATED;
    }
}
