package com.acltabontabon.vortex.app.config;

import com.acltabontabon.vortex.ai.AiSettings;
import com.acltabontabon.vortex.ai.OllamaAvailability;
import com.acltabontabon.vortex.ai.OllamaPerformanceAssistant;
import com.acltabontabon.vortex.app.VortexProperties;
import com.acltabontabon.vortex.core.application.ComparisonEvidenceAssembler;
import com.acltabontabon.vortex.core.application.EvidenceAssembler;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import com.acltabontabon.vortex.core.comparison.ExecutionComparison;
import com.acltabontabon.vortex.core.comparison.RegressionVerdict;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the optional local assistant.
 *
 * <p>Optional in the strongest sense: {@code ChatModel} may not exist at all, and Vortex must start
 * and work regardless. When it is absent the assistant bean is still present but reports itself
 * unavailable, so every caller takes the same well-tested path whether the reason is "no model
 * configured", "Ollama stopped" or "inference failed".
 *
 * <p>That uniformity is deliberate. Making AI absence a configuration branch would leave the
 * degraded path exercised only by whoever happened not to install Ollama.
 */
@Configuration(proxyBeanMethods = false)
public class AiConfiguration {

    @Bean
    AiSettings aiSettings(VortexProperties properties) {
        VortexProperties.Ai ai = properties.ai();
        return new AiSettings(ai.provider(), ai.baseUrl(), ai.model(), ai.timeout(), ai.logPrompts());
    }

    /**
     * Overrides Spring AI's own auto-configured {@code OllamaApi} bean (which builds an unconfigured
     * {@code RestClient} with no read timeout) so {@link AiSettings#timeout()} actually reaches the
     * HTTP call it names. Without this, a stuck Ollama call hangs indefinitely — {@code @Bean} methods
     * on a user {@code @Configuration} class are processed before deferred auto-configuration, so this
     * bean satisfies {@code OllamaApiAutoConfiguration}'s {@code @ConditionalOnMissingBean} and the
     * library's own bean is never created.
     */
    @Bean
    OllamaApi ollamaApi(RestClient.Builder builder, AiSettings settings) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(settings.timeout());
        return OllamaApi.builder()
                .baseUrl(settings.baseUrl())
                .restClientBuilder(builder.requestFactory(factory))
                .build();
    }

    @Bean
    OllamaAvailability ollamaAvailability(AiSettings settings) {
        return new OllamaAvailability(settings);
    }

    @Bean
    PerformanceAssistant performanceAssistant(ObjectProvider<ChatModel> chatModel,
            OllamaAvailability availability, AiSettings settings, EvidenceAssembler evidenceAssembler,
            ComparisonEvidenceAssembler comparisonEvidenceAssembler) {

        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return new UnconfiguredAssistant(settings);
        }
        return new OllamaPerformanceAssistant(model, availability, settings, evidenceAssembler,
                comparisonEvidenceAssembler);
    }

    /**
     * Stands in when no chat model is configured at all.
     *
     * <p>Reports the same shape of answer as a stopped Ollama, so the interface has one "AI is not
     * available, here is how to enable it" path rather than several.
     */
    private record UnconfiguredAssistant(AiSettings settings) implements PerformanceAssistant {

        @Override
        public Availability availability() {
            return Availability.unavailable(settings.provider(),
                    "No AI provider is configured.",
                    """
                    Vortex is fully usable without one — onboarding, workload configuration, \
                    execution, threshold evaluation, history and reports are unaffected.

                    To enable local analysis, install Ollama from https://ollama.com, start it, \
                    and choose a model under Settings → Local AI.""");
        }

        @Override
        public java.util.Optional<String> explainWorkload(
                com.acltabontabon.vortex.core.capacity.ProductionObservation observation,
                java.util.List<String> calculatedSuggestions) {
            return java.util.Optional.empty();
        }

        @Override
        public com.acltabontabon.vortex.core.analysis.Analysis analyze(com.acltabontabon.vortex.core.shared.ExecutionId executionId,
                com.acltabontabon.vortex.core.plan.EffectiveTestPlan plan,
                com.acltabontabon.vortex.core.analysis.DeterministicSummary summary) {
            return com.acltabontabon.vortex.core.analysis.Analysis.failed(
                    com.acltabontabon.vortex.core.shared.AnalysisId.generate(), executionId,
                    availability().problem() + " " + availability().remedy());
        }

        @Override
        public ComparisonAnalysis compareExecutions(TestExecution baseline, TestExecution candidate,
                ExecutionComparison comparison, RegressionVerdict verdict) {
            return ComparisonAnalysis.failed(com.acltabontabon.vortex.core.shared.AnalysisId.generate(),
                    baseline.id(), candidate.id(), availability().problem() + " " + availability().remedy());
        }
    }
}
