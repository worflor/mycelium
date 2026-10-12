package mycelium.item_presentation.v1.model;

import java.util.List;
import java.util.Map;

/**
 * What a compilation hands a platform adapter, and what the adapter evaluates at run time: the program's read inputs and
 * cases, the selections they collapse to and their locked codes. The adapter asks {@link #select} for a stack's
 * selection and writes {@link #selectorOf} onto the stack; the generated assets were compiled from the same plan.
 */
public interface Plan {
    /** A live selection: its key (the model reference it draws) and its locked legacy code. */
    record Entry(String key, int code) {
    }

    String item();

    Realization realization();

    String base();

    /** The program with the inputs no case reads dropped. */
    Program program();

    /** The selections the program can produce, other than the base model, ascending by key. */
    List<Entry> entries();

    /** The selection this stack draws. Total: a stack outside the program's domains draws the base model, flagged. */
    Selection select(ComponentView view);

    /** The selection these input values draw (a tuple that covers the program's read inputs). */
    Selection select(Map<String, Object> tuple);

    /** What to write onto a stack for this selection, in this plan's realization. */
    Selector selectorOf(Selection selection);

    /** The plan as a tree of JSON values, the form the build writes as a resource. */
    Map<String, Object> toTree();
}
