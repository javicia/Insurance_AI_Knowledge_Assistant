package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.application.aisystem.AiSystemRegistryService;
import com.rag.springai.insuranceai.application.governance.ModelRegistryService;
import com.rag.springai.insuranceai.application.governance.PromptRegistryService;
import com.rag.springai.insuranceai.application.governance.RiskAssessmentService;
import com.rag.springai.insuranceai.domain.aisystem.AiSystem;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.aisystem.HumanOversightRequirement;
import com.rag.springai.insuranceai.domain.governance.RiskAssessmentId;
import com.rag.springai.insuranceai.domain.model.AiModel;
import com.rag.springai.insuranceai.domain.prompt.Prompt;
import com.rag.springai.insuranceai.domain.prompt.PromptId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI Governance REST API (brief FASE 9 section 30): AI System Registry, Model Registry, Prompt
 * Registry and Risk Assessment. Thin controller - all decisions live in the application services
 * it delegates to (brief section 42).
 */
@RestController
@RequestMapping("/api/governance")
@Tag(name = "AI Governance", description = "AI System Registry, Model Registry, Prompt Registry and Risk "
        + "Assessment - real, queryable aggregates (V5__ai_governance.sql), not documentation-only claims.")
class GovernanceController {

    private final AiSystemRegistryService aiSystemRegistryService;
    private final ModelRegistryService modelRegistryService;
    private final PromptRegistryService promptRegistryService;
    private final RiskAssessmentService riskAssessmentService;

    GovernanceController(AiSystemRegistryService aiSystemRegistryService, ModelRegistryService modelRegistryService,
            PromptRegistryService promptRegistryService, RiskAssessmentService riskAssessmentService) {
        this.aiSystemRegistryService = aiSystemRegistryService;
        this.modelRegistryService = modelRegistryService;
        this.promptRegistryService = promptRegistryService;
        this.riskAssessmentService = riskAssessmentService;
    }

    @GetMapping("/ai-systems")
    @Operation(summary = "List all registered AI systems")
    List<AiSystemResponse> listAiSystems() {
        return aiSystemRegistryService.list().stream().map(AiSystemResponse::from).toList();
    }

    @GetMapping("/ai-systems/{id}")
    @Operation(summary = "Get an AI system's registry entry",
            description = "Includes purpose, owner, prohibited use, risk classification and the human "
                    + "oversight requirement.")
    AiSystemResponse getAiSystem(@PathVariable String id) {
        return AiSystemResponse.from(aiSystemRegistryService.get(AiSystemId.of(id)));
    }

    @PostMapping("/ai-systems")
    @Operation(summary = "Register a new AI system")
    ResponseEntity<AiSystemResponse> registerAiSystem(@RequestBody RegisterAiSystemRequest request) {
        HumanOversightRequirement oversight = new HumanOversightRequirement(request.humanOversightRequired(),
                request.humanOversightWhenRequired(), request.humanOversightEscalationCondition(),
                request.humanOversightDecisionResponsibility());
        AiSystem aiSystem = aiSystemRegistryService.register(request.name(), request.purpose(), request.owner(),
                request.intendedUse(), request.prohibitedUse(), request.riskClassification(), oversight);
        return ResponseEntity.status(HttpStatus.CREATED).body(AiSystemResponse.from(aiSystem));
    }

    @PostMapping("/ai-systems/{id}/activate")
    @Operation(summary = "Activate an AI system")
    AiSystemResponse activateAiSystem(@PathVariable String id) {
        return AiSystemResponse.from(aiSystemRegistryService.activate(AiSystemId.of(id)));
    }

    @GetMapping("/ai-systems/{aiSystemId}/models")
    @Operation(summary = "List the models registered for an AI system")
    List<ModelResponse> listModels(@PathVariable String aiSystemId) {
        return modelRegistryService.listByAiSystem(AiSystemId.of(aiSystemId)).stream().map(ModelResponse::from)
                .toList();
    }

    @PostMapping("/ai-systems/{aiSystemId}/models")
    @Operation(summary = "Register a provider/model combination against an AI system (the model allow-list)")
    ResponseEntity<ModelResponse> registerModel(@PathVariable String aiSystemId,
            @RequestBody RegisterModelRequest request) {
        AiModel model = modelRegistryService.register(AiSystemId.of(aiSystemId), request.provider(),
                request.modelIdentifier(), request.version(), request.capabilities(), request.intendedPurpose());
        return ResponseEntity.status(HttpStatus.CREATED).body(ModelResponse.from(model));
    }

    @GetMapping("/ai-systems/{aiSystemId}/prompts")
    @Operation(summary = "List every prompt version drafted for an AI system")
    List<PromptResponse> listPrompts(@PathVariable String aiSystemId) {
        return promptRegistryService.listByAiSystem(AiSystemId.of(aiSystemId)).stream().map(PromptResponse::from)
                .toList();
    }

    @GetMapping("/prompts/{promptKey}/active")
    @Operation(summary = "Get the currently active prompt for a key",
            description = "This is the exact prompt content AskInsuranceKnowledgeUseCase fetches for every "
                    + "grounded answer - the Prompt Registry is load-bearing, not decorative.")
    ResponseEntity<PromptResponse> getActivePrompt(@PathVariable String promptKey) {
        return promptRegistryService.findActive(promptKey)
                .map(prompt -> ResponseEntity.ok(PromptResponse.from(prompt)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/prompts/{promptKey}/versions")
    @Operation(summary = "List every version ever drafted for a prompt key")
    List<PromptResponse> listPromptVersions(@PathVariable String promptKey) {
        return promptRegistryService.listByKey(promptKey).stream().map(PromptResponse::from).toList();
    }

    @PostMapping("/ai-systems/{aiSystemId}/prompts")
    @Operation(summary = "Draft a new prompt version",
            description = "Checksum (SHA-256) is always recomputed from content server-side, never trusted "
                    + "from the request.")
    ResponseEntity<PromptResponse> draftPrompt(@PathVariable String aiSystemId,
            @RequestBody DraftPromptRequest request) {
        Prompt prompt = promptRegistryService.draft(AiSystemId.of(aiSystemId), request.promptKey(),
                request.content(), request.author(), request.changeReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(PromptResponse.from(prompt));
    }

    @PostMapping("/prompts/{id}/activate")
    @Operation(summary = "Activate a prompt version",
            description = "Retires any other ACTIVE prompt with the same key first - at most one ACTIVE "
                    + "prompt per key.")
    PromptResponse activatePrompt(@PathVariable String id) {
        return PromptResponse.from(promptRegistryService.activate(PromptId.of(id)));
    }

    @GetMapping("/ai-systems/{aiSystemId}/risk-assessments")
    @Operation(summary = "List risk assessments recorded for an AI system")
    List<RiskAssessmentResponse> listRiskAssessments(@PathVariable String aiSystemId) {
        return riskAssessmentService.listByAiSystem(AiSystemId.of(aiSystemId)).stream()
                .map(RiskAssessmentResponse::from)
                .toList();
    }

    @PostMapping("/ai-systems/{aiSystemId}/risk-assessments")
    @Operation(summary = "Draft a risk assessment",
            description = "A self-assessment record with documented rationale/controls/residual risk - see "
                    + "docs/governance/AI_ACT.md for why this is not a legal determination.")
    ResponseEntity<RiskAssessmentResponse> draftRiskAssessment(@PathVariable String aiSystemId,
            @RequestBody DraftRiskAssessmentRequest request) {
        var assessment = riskAssessmentService.draft(AiSystemId.of(aiSystemId), request.classification(),
                request.rationale(), request.controls(), request.residualRisk(), request.reviewer());
        return ResponseEntity.status(HttpStatus.CREATED).body(RiskAssessmentResponse.from(assessment));
    }

    @PostMapping("/risk-assessments/{id}/approve")
    @Operation(summary = "Approve a risk assessment",
            description = "Also updates the owning AI system's current risk classification to match.")
    RiskAssessmentResponse approveRiskAssessment(@PathVariable String id) {
        return RiskAssessmentResponse.from(riskAssessmentService.approve(RiskAssessmentId.of(id)));
    }
}
