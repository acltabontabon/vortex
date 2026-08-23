package dev.vortex.app.web;

import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this machine can currently do, as JSON.
 *
 * <p>Backs the app shell's status bar — the same {@link RuntimeStatus} the top bar's runtime menu
 * used to read directly out of the Thymeleaf model. See {@link RuntimeStatus} for why this is
 * memoised and why it never claims a single word like "Ready".
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeApiController {

    private final RuntimeStatus runtimeStatus;

    public RuntimeApiController(RuntimeStatus runtimeStatus) {
        this.runtimeStatus = Objects.requireNonNull(runtimeStatus, "runtimeStatus");
    }

    public record CheckDto(String name, boolean required, boolean ok, String mark, String detail,
            String remedy) {}

    public record RuntimeSummaryDto(List<CheckDto> checks, int satisfied, int total,
            boolean requirementsMet) {}

    @GetMapping
    public RuntimeSummaryDto current() {
        return toDto(runtimeStatus.current());
    }

    @PostMapping("/refresh")
    public RuntimeSummaryDto refresh() {
        return toDto(runtimeStatus.refresh());
    }

    private RuntimeSummaryDto toDto(RuntimeStatus.Summary summary) {
        List<CheckDto> checks = summary.checks().stream()
                .map(check -> new CheckDto(check.name(), check.required(), check.isOk(),
                        check.mark(), check.detail(), check.remedy()))
                .toList();
        return new RuntimeSummaryDto(checks, summary.satisfied(), summary.total(),
                summary.requirementsMet());
    }
}
