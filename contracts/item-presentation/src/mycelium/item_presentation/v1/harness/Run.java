package mycelium.item_presentation.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.item_presentation.v1.fixtures.DescendingOverrides;
import mycelium.item_presentation.v1.fixtures.EnumerationOrderCodes;
import mycelium.item_presentation.v1.fixtures.FaultyArtefacts;
import mycelium.item_presentation.v1.fixtures.FaultyBounds;
import mycelium.item_presentation.v1.fixtures.FaultyLock;
import mycelium.item_presentation.v1.fixtures.FaultyPlan;
import mycelium.item_presentation.v1.fixtures.FaultyProjection;
import mycelium.item_presentation.v1.fixtures.FaultyTarget;
import mycelium.item_presentation.v1.fixtures.HashOrderEmitter;
import mycelium.item_presentation.v1.fixtures.InvertedContexts;
import mycelium.item_presentation.v1.fixtures.LeakyProjection;
import mycelium.item_presentation.v1.fixtures.LenientCompiler;
import mycelium.item_presentation.v1.fixtures.NoOpCompiler;
import mycelium.item_presentation.v1.fixtures.OffByOneLegacy;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("item.presentation@1",
                () -> new SigilContractTests(new ReferenceSigil()).run(),
                List.of(
                        new ContractRun.Broken("EnumerationOrderCodes",
                                () -> new SigilContractTests(new EnumerationOrderCodes()).run(),
                                "S3:existing-codes-kept"),
                        new ContractRun.Broken("DescendingOverrides",
                                () -> new SigilContractTests(new DescendingOverrides()).run(),
                                "S4:overrides-strictly-ascending"),
                        new ContractRun.Broken("OffByOneLegacy",
                                () -> new SigilContractTests(new OffByOneLegacy()).run(),
                                "S2:legacy-draws-evaluated-model"),
                        new ContractRun.Broken("LeakyProjection",
                                () -> new SigilContractTests(new LeakyProjection()).run(),
                                "S5:server-private-rejected"),
                        new ContractRun.Broken("HashOrderEmitter",
                                () -> new SigilContractTests(new HashOrderEmitter()).run(),
                                "S6:canonical-json"),
                        new ContractRun.Broken("InvertedContexts",
                                () -> new SigilContractTests(new InvertedContexts()).run(),
                                "S8:definition-draws-context-rules"),
                        new ContractRun.Broken("LastMatchWinsPlan",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.LAST_MATCH_WINS)).run(),
                                "S1:first-match-wins"),
                        new ContractRun.Broken("UnmatchedDrawsFirstSelection",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.NO_CASE_DRAWS_FIRST_SELECTION)).run(),
                                "S1:base-when-no-case"),
                        new ContractRun.Broken("PlanCodeOffTable",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.CODE_OFF_THE_TABLE)).run(),
                                "S1:one-selection-per-tuple"),
                        new ContractRun.Broken("StrayStackDrawsDefault",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.IGNORES_DOMAIN_FLAG)).run(),
                                "S1:out-of-domain-draws-base"),
                        new ContractRun.Broken("AbsentComponentIsForeign",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.ABSENT_IS_FOREIGN)).run(),
                                "S1:absent-component-reads-default"),
                        new ContractRun.Broken("UnprunedPlan",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.KEEPS_UNREAD_INPUTS)).run(),
                                "S1:unread-input-dropped"),
                        new ContractRun.Broken("FlagReadAsPresence",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.FLAG_AS_PRESENCE)).run(),
                                "S2:adapter-reading-agrees"),
                        new ContractRun.Broken("FieldReadByInputName",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.FIELD_BY_INPUT_NAME)).run(),
                                "S2:carrier-view-agrees"),
                        new ContractRun.Broken("TruncatedPlanResource",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.RESOURCE_LACKS_A_SELECTION)).run(),
                                "S2:plan-resource-agrees"),
                        new ContractRun.Broken("PlanResourceWithoutRules",
                                () -> new SigilContractTests(new FaultyPlan(FaultyPlan.Flaw.RESOURCE_LACKS_CONTEXT_RULES)).run(),
                                "S8:plan-keeps-rules"),
                        new ContractRun.Broken("RetiredRowsDropped",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.RETIRED_ROWS_DROPPED)).run(),
                                "S2:retired-code-draws-base"),
                        new ContractRun.Broken("LastCaseDropped",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.LAST_CASE_DROPPED)).run(),
                                "S2:modern-draws-evaluated-model"),
                        new ContractRun.Broken("SilentEmptyProgram",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.EMPTY_PROGRAM_WRITES_NOTHING)).run(),
                                "S2:artefact-emitted"),
                        new ContractRun.Broken("ReversedCases",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.CASES_IN_REVERSE)).run(),
                                "S6:cases-in-key-order"),
                        new ContractRun.Broken("CrLfArtefacts",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.CRLF_LINE_ENDINGS)).run(),
                                "S6:ascii-and-line-feeds"),
                        new ContractRun.Broken("LocaleFoldedArtefacts",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.LOCALE_CASE_FOLDING)).run(),
                                "S6:locale-independent"),
                        new ContractRun.Broken("BuildStampedArtefacts",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.BUILD_STAMP)).run(),
                                "S6:repeat-identical"),
                        new ContractRun.Broken("LegacyAppliesContexts",
                                () -> new SigilContractTests(new FaultyArtefacts(FaultyArtefacts.Flaw.LEGACY_APPLIES_CONTEXTS)).run(),
                                "S8:legacy-ignores-contexts"),
                        new ContractRun.Broken("InventedCodes",
                                () -> new SigilContractTests(new FaultyLock(FaultyLock.Flaw.INVENTS_MISSING_CODES)).run(),
                                "S3:missing-lock-lists-lines"),
                        new ContractRun.Broken("NamespacelessLockLines",
                                () -> new SigilContractTests(new FaultyLock(FaultyLock.Flaw.LINES_LACK_NAMESPACE)).run(),
                                "S3:lines-complete-the-lock"),
                        new ContractRun.Broken("GappedLockLines",
                                () -> new SigilContractTests(new FaultyLock(FaultyLock.Flaw.LINES_SKIP_A_CODE)).run(),
                                "S3:new-selections-append"),
                        new ContractRun.Broken("BlindToUnknownComponents",
                                () -> new SigilContractTests(new FaultyProjection(Diagnostic.Kind.CATALOG)).run(),
                                "S5:unknown-component-rejected"),
                        new ContractRun.Broken("BlindToKindMismatch",
                                () -> new SigilContractTests(new FaultyProjection(Diagnostic.Kind.TYPE)).run(),
                                "S5:type-mismatch-rejected"),
                        new ContractRun.Broken("MechanismlessCompiles",
                                () -> new SigilContractTests(new FaultyTarget(FaultyTarget.Flaw.NO_MECHANISM_COMPILES_ANYWAY)).run(),
                                "R1:no-realization-rejected"),
                        new ContractRun.Broken("UnguardedOwnModel",
                                () -> new SigilContractTests(new FaultyTarget(FaultyTarget.Flaw.LEGACY_SKIPS_CLASH_CHECK)).run(),
                                "R2:model-clash-rejected"),
                        new ContractRun.Broken("ExclusiveCap",
                                () -> new SigilContractTests(new FaultyBounds(FaultyBounds.Flaw.CAP_IS_EXCLUSIVE)).run(),
                                "S7:product-at-cap-accepted"),
                        new ContractRun.Broken("EnumeratingCap",
                                () -> new SigilContractTests(new FaultyBounds(FaultyBounds.Flaw.COUNTS_AFTER_ENUMERATING)).run(),
                                "S7:product-over-cap-rejected-before-enumeration"),
                        new ContractRun.Broken("TrustsBuiltProgram",
                                () -> new SigilContractTests(new LenientCompiler(LenientCompiler.Mode.UNDECLARED_GUARDS)).run(),
                                "P1:compiler-rechecks-program"),
                        new ContractRun.Broken("DuplicateRulesDropped",
                                () -> new SigilContractTests(new LenientCompiler(LenientCompiler.Mode.DUPLICATE_RULES)).run(),
                                "S8:one-rule-per-model"),
                        new ContractRun.Broken("UnknownContextsDropped",
                                () -> new SigilContractTests(new LenientCompiler(LenientCompiler.Mode.UNKNOWN_CONTEXTS)).run(),
                                "S8:unknown-context-rejected"),
                        new ContractRun.Broken("UndrawnRulesDropped",
                                () -> new SigilContractTests(new LenientCompiler(LenientCompiler.Mode.UNDRAWN_RULES)).run(),
                                "S8:rule-for-undrawn-model-rejected"),
                        new ContractRun.Broken("NoOpCompiler",
                                () -> new SigilContractTests(new NoOpCompiler()).run(),
                                "S1:totality-agrees-with-evaluator")));
        System.exit(exit);
    }
}
