package mycelium.flow.v1.model;

@FunctionalInterface
public interface TransferExecutor {
    TransferOutcome execute(FlowBackend source, FlowBackend destination, ResourceKey resource,
                            long requested, FlowTransaction tx, Feedback feedback);
}
