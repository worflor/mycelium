package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Reading something that may be refused: the value, or every reason it was. */
public sealed interface Result<T> {
    record Ok<T>(T value) implements Result<T> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failed<T>(List<Diagnostic> diagnostics) implements Result<T> {
        public Failed {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("a refusal gives at least one reason");
            }
            diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }
}
