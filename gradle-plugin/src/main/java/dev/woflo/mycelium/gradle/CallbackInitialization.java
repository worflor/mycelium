package dev.woflo.mycelium.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * Callback dependency flow over the consumer's compiled classes. It follows field bindings and callback escapes,
 * without a loader rule table. A finding is a potential ordering risk, not a proof of native event order.
 */
final class CallbackInitialization {
    record Method(String owner, String name, String desc) {
        public String toString() {
            return owner + "#" + name + desc;
        }
    }
    record Field(String owner, String name, String desc) {
        public String toString() {
            return owner + "." + name;
        }
    }
    record Escape(String registration, Method callback, Field dependency, String read, boolean cleared, Set<Parameter> conditional) {
        Escape(String registration, Method callback, Field dependency, String read, boolean cleared) {
            this(registration, callback, dependency, read, cleared, Set.of());
        }
        Escape { conditional = Set.copyOf(conditional); }
    }
    record ParameterEscape(int local, String registration, Set<Field> bound, Set<Field> clears,
                           Signature invocation, Map<Field, Set<Parameter>> conditional) {
        ParameterEscape(int local, String registration, Set<Field> bound, Set<Field> clears, Signature invocation) {
            this(local, registration, bound, clears, invocation, Map.of());
        }
        ParameterEscape { conditional = freezeSets(conditional); }
    }
    record Thrown(String type, boolean exact) {}
    enum Relation { YES, NO, UNKNOWN }
    enum Coverage { DISJOINT, POSSIBLE, COVERS }
    /** Known null paths and formal dependencies; absence is not a non-null proof. */
    record ReferenceDependencies(boolean nullable, Set<Parameter> parameters) {
        static final ReferenceDependencies NONE = new ReferenceDependencies(false, Set.of());
        ReferenceDependencies { parameters = Set.copyOf(parameters); }
        ReferenceDependencies join(ReferenceDependencies other) {
            if ((nullable || !other.nullable) && parameters.containsAll(other.parameters)) return this;
            if ((other.nullable || !nullable) && other.parameters.containsAll(parameters)) return other;
            Set<Parameter> joined = new HashSet<>(parameters); joined.addAll(other.parameters);
            return new ReferenceDependencies(nullable || other.nullable, joined);
        }
    }
    /** Binding facts belong to the produced reference, even after its source field changes. */
    record ReferenceSnapshot(ReferenceDependencies dependencies, boolean bound, boolean cleared) {
        ReferenceSnapshot join(ReferenceSnapshot other) {
            if (equals(other)) return this;
            return new ReferenceSnapshot(dependencies.join(other.dependencies), bound && other.bound, cleared || other.cleared);
        }
    }
    record ExitEffects(Set<Field> bound, Set<Field> clears, BitSet initialized, Map<Field, Set<Parameter>> conditional) {
        ExitEffects(Set<Field> bound, Set<Field> clears, BitSet initialized) { this(bound, clears, initialized, Map.of()); }
        ExitEffects {
            bound = Set.copyOf(bound);
            clears = Set.copyOf(clears);
            initialized = (BitSet) initialized.clone();
            conditional = freezeSets(conditional);
        }
    }
    record ConditionalRead(Field dependency, String read, Set<Parameter> parameters) {
        ConditionalRead { parameters = Set.copyOf(parameters); }
    }
    record Summary(Map<Field, String> needs, Set<Field> ensures, Set<Field> clears, Set<Escape> escapes,
                   Set<ParameterEscape> parameterEscapes, BitSet initializedClasses, Set<Field> invalidatedNeeds,
                   boolean returnsNormally, Map<Thrown, ExitEffects> exceptionalExits,
                   Map<Field, Set<Parameter>> conditional, ReferenceDependencies returnedReference, Set<ConditionalRead> conditionalReads) {
        Summary { conditionalReads = Set.copyOf(conditionalReads); initializedClasses = (BitSet) initializedClasses.clone(); exceptionalExits = Map.copyOf(exceptionalExits); conditional = freezeSets(conditional); }
        static Summary empty() {
            return new Summary(Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), new BitSet(), Set.of(), false, Map.of(), Map.of(), ReferenceDependencies.NONE, Set.of());
        }
    }
    record Finding(Method root, String registration, Method callback, Field dependency, String read) {
    }
    record Parameter(Method method, int local) {}
    record ReturnedCallbacks(Method method) {}
    record Dependency(String factType, String fact, Method dependent, String dependentContext) {}
    record Context(Method method, Map<Parameter, Set<Method>> arguments) {
        public String toString() {
            return method + arguments.entrySet().stream().sorted(Comparator.comparingInt(entry -> entry.getKey().local))
                    .map(entry -> entry.getKey().local + "=" + entry.getValue().stream().map(Method::toString).sorted().toList())
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
    }
    record Signature(String name, String descriptor) {}
    record Value(Set<AbstractInsnNode> origins) {
        static Value of(SourceValue value) { return new Value(Set.copyOf(value.insns)); }
    }
    record State(Set<Field> bound, Set<Value> nonnull, BitSet initialized, Set<Field> cleared,
                 Map<AbstractInsnNode, Set<Thrown>> caught, Map<Field, Set<Parameter>> conditional,
                 Map<AbstractInsnNode, ReferenceSnapshot> references) {
        State(Set<Field> bound, Set<Value> nonnull, BitSet initialized, Set<Field> cleared) {
            this(bound, nonnull, initialized, cleared, Map.of(), Map.of(), Map.of());
        }
        State {
            bound = Set.copyOf(bound);
            nonnull = Set.copyOf(nonnull);
            initialized = (BitSet) initialized.clone();
            cleared = Set.copyOf(cleared);
            caught = freezeSets(caught);
            conditional = freezeSets(conditional);
            references = Map.copyOf(references);
        }
    }
    record FlowPoint(int instruction, Map<AbstractInsnNode, Set<Thrown>> caught) {}
    static <K, V> Map<K, Set<V>> freezeSets(Map<K, Set<V>> values) {
        if (values.isEmpty()) return Map.of();
        Map<K, Set<V>> snapshot = new HashMap<>();
        values.forEach((key, set) -> snapshot.put(key, Set.copyOf(set)));
        return Map.copyOf(snapshot);
    }
    final Map<String, ClassNode> classes = new TreeMap<>();
    final Map<String, Integer> classIds = new HashMap<>();
    int classId(String name) { return classIds.computeIfAbsent(name, ignored -> classIds.size()); }
    final Map<Method, MethodNode> methods = new LinkedHashMap<>();
    final Map<Method, Frame<SourceValue>[]> sourceFrames = new HashMap<>();
    final Set<Field> referenceFields = new HashSet<>();
    final Set<Field> declaredFields = new HashSet<>();
    final Map<Field, Field> resolvedFields = new HashMap<>();
    final Map<Method, Method> resolvedStaticMethods = new HashMap<>();
    final Map<Method, Set<Method>> returned = new HashMap<>();
    final Map<Method, Summary> summaries = new HashMap<>();
    final List<String> analysisErrors = new ArrayList<>();
    final Map<String, Set<Field>> classInitializationBindings = new HashMap<>();
    final List<Finding> findings = new ArrayList<>();
    final Set<Method> entryRoots = new LinkedHashSet<>();
    final Map<AbstractInsnNode, Parameter> parameters = new IdentityHashMap<>();
    final Map<TryCatchBlockNode, AbstractInsnNode> exceptionMarkers = new IdentityHashMap<>();
    final Map<AbstractInsnNode, String> exceptionTypes = new IdentityHashMap<>();
    final Map<String, String> exceptionParents = new HashMap<>();
    final Set<String> missingExceptionTypes = new HashSet<>();
    final Map<AbstractInsnNode, Frame<SourceValue>> instructionFrames = new IdentityHashMap<>();
    final Map<Field, Set<Method>> fieldCallbacks = new HashMap<>();
    final Set<Field> writtenFields = new HashSet<>();
    final Set<Field> constantReferences = new HashSet<>();
    final Set<Field> finalReferences = new HashSet<>();
    final Set<Field> immutableBindings = new HashSet<>();
    final Map<String, Set<String>> lineageCache = new HashMap<>();
    final Map<Method, Set<Method>> virtualDispatchCache = new HashMap<>();
    final Map<String, Set<Method>> constructedCallbackCache = new HashMap<>();
    final Map<String, Set<String>> concreteSubtypes = new HashMap<>();
    final Map<MethodInsnNode, Set<Method>> callTargetCache = new IdentityHashMap<>();
    final Map<Method, int[]> sourceLines = new HashMap<>();
    final Map<Method, Set<Signature>> functionalSignatures = new HashMap<>();
    final Map<Parameter, Set<Method>> contextValues = new HashMap<>();
    final Map<Context, Summary> contextSummaries = new HashMap<>();
    final Map<Object, Set<Object>> dependents = new HashMap<>();
    final Deque<Object> pendingSummaries = new ArrayDeque<>();
    final Set<Object> queuedSummaries = new HashSet<>();
    Object evaluating;
    final Map<Object, Set<Object>> initializationCalls = new HashMap<>();
    final Map<Object, BitSet> initializationReachability = new HashMap<>();
    Set<Object> currentInitializationCalls = new LinkedHashSet<>();
    long initializationPropagations;
    int initializationPending;
    String phase = "bytecode provenance";
    long summaryEvaluations;
    final Map<Object, Long> summaryUpdates = new HashMap<>();
    Object hottestSummary;
    long mostUpdates;
    long dataflowSteps;
    record Progress(long summaryEvaluations, int pendingSummaries, int callbackContexts, long dataflowSteps,
                    String hottestSummary, long summaryUpdates, String phase, int initializationPending, long initializationPropagations) {}
    java.util.function.Consumer<Progress> progress;
    long lastProgress;
    boolean progressStarted;
    void progressPoint() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Bytecode analysis cancelled");
        if (progress != null && (!progressStarted || System.nanoTime() - lastProgress >= 5_000_000_000L)) {
            lastProgress = System.nanoTime();
            progressStarted = true;
            progress.accept(new Progress(summaryEvaluations, pendingSummaries.size(), contextSummaries.size(), dataflowSteps,
                    hottestSummary == null ? "" : hottestSummary.toString(), mostUpdates, phase, initializationPending, initializationPropagations));
        }
    }

    void dependOn(Object fact) {
        if (evaluating != null) dependents.computeIfAbsent(fact, ignored -> new LinkedHashSet<>()).add(evaluating);
    }
    void enqueue(Object method) {
        if (queuedSummaries.add(method)) {
            if (method instanceof Context) pendingSummaries.addFirst(method);
            else pendingSummaries.addLast(method);
        }
    }
    void changed(Object fact) {
        dependents.getOrDefault(fact, Set.of()).forEach(this::enqueue);
    }
    Summary summary(Method method) {
        dependOn(method);
        return summaries.getOrDefault(method, Summary.empty());
    }
    void prepare() {
        for (ClassNode c : classes.values()) {
            for (FieldNode f : c.fields) {
                declaredFields.add(new Field(c.name, f.name, f.desc));
                if ((f.access & Opcodes.ACC_STATIC) != 0 && reference(f.desc)) {
                    Field key = new Field(c.name, f.name, f.desc);
                    referenceFields.add(key);
                    if (f.value != null) constantReferences.add(key);
                    if ((f.access & Opcodes.ACC_FINAL) != 0) finalReferences.add(key);
                }
            }
            for (MethodNode m : c.methods) {
                Method k = new Method(c.name, m.name, m.desc);
                methods.put(k, m);
                if ((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0) continue;
                try {
                    sourceFrames.put(k, new Analyzer<>(new SourceInterpreter(Opcodes.ASM9) {
                        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
                            AbstractInsnNode marker = new InsnNode(Opcodes.NOP);
                            parameters.put(marker, new Parameter(k, local));
                            return new SourceValue(type.getSize(), marker);
                        }
                        @Override public SourceValue newExceptionValue(TryCatchBlockNode block, Frame<SourceValue> frame, Type type) {
                            AbstractInsnNode marker = exceptionMarkers.computeIfAbsent(block, ignored -> new InsnNode(Opcodes.NOP));
                            exceptionTypes.put(marker, type.getInternalName());
                            return new SourceValue(1, marker);
                        }
                        // Keep the producing instruction through loads, stores and casts.
                        @Override public SourceValue copyOperation(AbstractInsnNode i, SourceValue v) {
                            return v;
                        }
                        @Override public SourceValue unaryOperation(AbstractInsnNode i, SourceValue v) {
                            return i.getOpcode()==Opcodes.CHECKCAST ? v : super.unaryOperation(i, v);
                        }
                    }).analyze(c.name, m));
                    for (int i = 0; i < m.instructions.size(); i++) {
                        instructionFrames.put(m.instructions.get(i), sourceFrames.get(k)[i]);
                    }
                    int[] lines = new int[m.instructions.size()];
                    int line = -1;
                    for (int i = 0; i < lines.length; i++) {
                        if (m.instructions.get(i) instanceof LineNumberNode number) line = number.line;
                        lines[i] = line;
                    }
                    sourceLines.put(k, lines);
                } catch (AnalyzerException x) {
                    analysisErrors.add(k+": "+x.getMessage());
                }
            }
        }
        for (MethodNode body : methods.values()) {
            for (AbstractInsnNode instruction : body.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) writtenFields.add(field(field));
            }
        }
    }
    static boolean reference(String d) {
        return d.startsWith("L")||d.startsWith("[");
    }
    Field field(FieldInsnNode i) {
        Field symbolic = new Field(i.owner, i.name, i.desc);
        Field resolved = resolveField(symbolic);
        return resolved == null ? symbolic : resolved;
    }
    Field resolveField(Field symbolic) {
        if (resolvedFields.containsKey(symbolic)) return resolvedFields.get(symbolic);
        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.push(symbolic.owner);
        Field resolved = null;
        while (!work.isEmpty()) {
            String owner = work.pop();
            if (!seen.add(owner)) continue;
            ClassNode node = classes.get(owner);
            // An absent earlier branch might contain the declaration. Do not credit a later one.
            if (node == null) break;
            Field candidate = new Field(owner, symbolic.name, symbolic.desc);
            if (declaredFields.contains(candidate)) { resolved = candidate; break; }
            if (node.superName != null && (node.access & Opcodes.ACC_INTERFACE) == 0) work.push(node.superName);
            for (int index = node.interfaces.size() - 1; index >= 0; index--) work.push(node.interfaces.get(index));
        }
        resolvedFields.put(symbolic, resolved);
        return resolved;
    }
    Method staticMethod(Method symbolic) {
        if (resolvedStaticMethods.containsKey(symbolic)) return resolvedStaticMethods.get(symbolic);
        Set<String> seen = new HashSet<>();
        String owner = symbolic.owner;
        Method resolved = null;
        while (owner != null && seen.add(owner)) {
            ClassNode node = classes.get(owner);
            if (node == null) break;
            Method candidate = new Method(owner, symbolic.name, symbolic.desc);
            MethodNode body = methods.get(candidate);
            if (body != null) {
                if ((body.access & Opcodes.ACC_STATIC) != 0) resolved = candidate;
                break;
            }
            if ((node.access & Opcodes.ACC_INTERFACE) != 0) break;
            owner = node.superName;
        }
        resolvedStaticMethods.put(symbolic, resolved);
        return resolved;
    }
    String activeClass(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (instruction instanceof TypeInsnNode type && opcode == Opcodes.NEW) return type.desc;
        if (instruction instanceof FieldInsnNode field && (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC)) {
            Field resolved = resolveField(new Field(field.owner, field.name, field.desc));
            return resolved == null ? null : resolved.owner;
        }
        if (instruction instanceof MethodInsnNode call && opcode == Opcodes.INVOKESTATIC) {
            Method resolved = staticMethod(method(call));
            return resolved == null ? null : resolved.owner;
        }
        return null;
    }
    static Method method(MethodInsnNode i) {
        return new Method(i.owner, i.name, i.desc);
    }
    static Method method(Handle h) {
        return new Method(h.getOwner(), h.getName(), h.getDesc());
    }
    String site(Method k, int index) {
        int line = sourceLines.get(k)[index];
        String source=classes.get(k.owner).sourceFile;
        if (source != null) source = source.replace('\\', '/').substring(source.replace('\\', '/').lastIndexOf('/') + 1);
        return source!=null&&line>0?source+":"+line+" ("+k+")":k.toString();
    }
    Set<Method> targets(SourceValue v) {
        Set<Method> out = new LinkedHashSet<>();
        if (v == null) return out;
        for (AbstractInsnNode p : v.insns) {
            Parameter parameter = parameters.get(p);
            if (parameter != null) out.addAll(contextValues.getOrDefault(parameter, Set.of()));
            if (p instanceof InvokeDynamicInsnNode d && d.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                for (Object arg : d.bsmArgs) {
                    if (arg instanceof Handle h) {
                        if (h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE) {
                            Frame<SourceValue> frame = instructionFrames.get(d);
                            int count = Type.getArgumentTypes(d.desc).length;
                            SourceValue receiver = frame != null && count > 0 ? frame.getStack(frame.getStackSize() - count) : null;
                            out.addAll(dispatch(method(h), receiver));
                        } else if (h.getTag() == Opcodes.H_INVOKESTATIC) {
                            Method resolved = staticMethod(method(h));
                            if (resolved != null) out.add(resolved);
                        } else if (methods.containsKey(method(h))) out.add(method(h));
                    }
                }
            }
            if (p instanceof MethodInsnNode m) {
                Method resolved = m.getOpcode() == Opcodes.INVOKESTATIC ? staticMethod(method(m)) : method(m);
                if (resolved != null) {
                    dependOn(new ReturnedCallbacks(resolved));
                    out.addAll(returned.getOrDefault(resolved, Set.of()));
                }
            }
            if (p instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
                dependOn(field(f));
                out.addAll(fieldCallbacks.getOrDefault(field(f), Set.of()));
            }
            if (p instanceof TypeInsnNode n && n.getOpcode() == Opcodes.NEW)
                out.addAll(constructedCallbacks(n.desc));
        }
        return out;
    }
    Set<Method> constructedCallbacks(String type) {
        return constructedCallbackCache.computeIfAbsent(type, owner -> {
            if (!classes.containsKey(owner) || lineage(owner).stream()
                    .noneMatch(parent -> classes.containsKey(parent) && !classes.get(parent).interfaces.isEmpty())) return Set.of();
            Set<Method> selected = new LinkedHashSet<>();
            Set<String> signatures = new HashSet<>();
            for (String parent : lineage(owner)) {
                ClassNode node = classes.get(parent);
                if (node == null) continue;
                for (MethodNode body : node.methods) {
                    Method key = new Method(parent, body.name, body.desc);
                    if (methods.containsKey(key) && (body.access & Opcodes.ACC_PUBLIC) != 0
                            && (body.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0
                            && !body.name.startsWith("<") && signatures.add(body.name + body.desc)) selected.add(key);
                }
            }
            return Set.copyOf(selected);
        });
    }
    Set<Field> origins(SourceValue v) {
        Set<Field> out = new HashSet<>();
        if (v != null) {
            for (AbstractInsnNode p : v.insns) {
                if (p instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && referenceFields.contains(field(f))) {
                    out.add(field(f));
                }
            }
        }
        return out;
    }

    Set<String> lineage(String type) {
        Set<String> cached = lineageCache.get(type);
        if (cached != null) return cached;
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) continue;
            ClassNode node = classes.get(current);
            if (node == null) continue;
            if (node.superName != null) pending.addFirst(node.superName);
            pending.addAll(node.interfaces);
        }
        Set<String> result = Collections.unmodifiableSet(seen);
        lineageCache.put(type, result);
        return result;
    }

    Method implementation(String type, Method declaration) {
        for (String parent : lineage(type)) {
            Method key = new Method(parent, declaration.name, declaration.desc);
            MethodNode body = methods.get(key);
            if (body != null && (body.access & Opcodes.ACC_ABSTRACT) == 0) return key;
        }
        return null;
    }

    Set<Method> dispatch(Method declaration, SourceValue receiver) {
        Set<String> types = new LinkedHashSet<>();
        boolean exact = receiver != null && !receiver.insns.isEmpty();
        if (receiver != null) {
            for (AbstractInsnNode source : receiver.insns) {
                if (source instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) types.add(type.desc);
                else exact = false;
            }
        }
        if (!exact) {
            Set<Method> cached = virtualDispatchCache.get(declaration);
            if (cached != null) return cached;
            types.clear();
            types.addAll(concreteSubtypes.getOrDefault(declaration.owner, Set.of()));
        }
        Set<Method> targets = new LinkedHashSet<>();
        for (String type : types) {
            Method selected = implementation(type, declaration);
            if (selected != null) targets.add(selected);
        }
        Set<Method> result = Set.copyOf(targets);
        if (!exact) virtualDispatchCache.put(declaration, result);
        return result;
    }

    Set<Method> callTargets(MethodInsnNode call, Frame<SourceValue> frame) {
        Set<Method> cached = callTargetCache.get(call);
        if (cached != null) return cached;
        Method declared = method(call);
        if (call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) {
            Method selected = call.getOpcode() == Opcodes.INVOKESTATIC ? staticMethod(declared) : implementation(call.owner, declared);
            Set<Method> targets = selected == null ? Set.of() : Set.of(selected);
            callTargetCache.put(call, targets);
            return targets;
        }
        int arguments = Type.getArgumentTypes(call.desc).length;
        SourceValue receiver = frame == null ? null : frame.getStack(frame.getStackSize() - arguments - 1);
        Set<Method> knownValues = targets(receiver);
        if (!knownValues.isEmpty()) {
            Signature signature = new Signature(call.name, call.desc);
            Set<Method> selected = new LinkedHashSet<>();
            for (Method value : knownValues) {
                if ((value.name.equals(call.name) && value.desc.equals(call.desc))
                        || functionalSignatures.getOrDefault(value, Set.of()).contains(signature)) selected.add(value);
            }
            Set<Method> targets = Set.copyOf(selected);
            callTargetCache.put(call, targets);
            return targets;
        }
        Set<Method> targets = Set.copyOf(dispatch(declared, receiver));
        callTargetCache.put(call, targets);
        return targets;
    }

    Summary combined(Set<Method> targets) {
        return combined(targets, null, null);
    }
    Summary atCall(Object key, Summary behavior, boolean recordInitialization) {
        if (evaluating != null) {
            if (recordInitialization) currentInitializationCalls.add(key);
            return behavior;
        }
        BitSet initialized = initializationReachability.getOrDefault(key, behavior.initializedClasses);
        return new Summary(behavior.needs, behavior.ensures, behavior.clears, behavior.escapes,
                behavior.parameterEscapes, initialized, behavior.invalidatedNeeds, behavior.returnsNormally, behavior.exceptionalExits,
                behavior.conditional, behavior.returnedReference, behavior.conditionalReads);
    }
    Summary specialized(Method target, MethodInsnNode call, Frame<SourceValue> frame, boolean recordInitialization) {
        Summary base = summary(target);
        if (call == null || frame == null || base.parameterEscapes.isEmpty() || !sourceFrames.containsKey(target)) return atCall(target, base, recordInitialization);
        Map<Parameter, Set<Method>> arguments = new HashMap<>();
        for (ParameterEscape effect : base.parameterEscapes) {
            Set<Method> values = targets(argument(call, frame, effect.local));
            if (!values.isEmpty()) arguments.put(new Parameter(target, effect.local), Set.copyOf(values));
        }
        if (arguments.isEmpty()) return atCall(target, base, recordInitialization);
        Context key = new Context(target, Map.copyOf(arguments));
        dependOn(key);
        if (!contextSummaries.containsKey(key)) {
            if (evaluating == null) {
                analysisErrors.add("callback context unresolved after fixed point; analysis incomplete");
                return atCall(target, base, recordInitialization);
            }
            contextSummaries.put(key, Summary.empty());
            enqueue(key);
        }
        return atCall(key, contextSummaries.get(key), recordInitialization);
    }
    Summary combined(Set<Method> targets, MethodInsnNode call, Frame<SourceValue> frame) {
        return combined(targets, call, frame, true);
    }
    Summary combined(Set<Method> targets, MethodInsnNode call, Frame<SourceValue> frame, boolean recordInitialization) {
        if (targets.isEmpty()) return null;
        Map<Field, String> needs = new LinkedHashMap<>();
        Set<Field> ensures = null;
        Set<Field> clears = new HashSet<>();
        Set<Escape> escapes = new LinkedHashSet<>();
        Set<ParameterEscape> parameterEscapes = new LinkedHashSet<>();
        BitSet initializedClasses = new BitSet();
        Set<Field> invalidatedNeeds = new HashSet<>();
        boolean returnsNormally = false;
        Map<Thrown, ExitEffects> exceptional = new HashMap<>();
        Map<Field, Set<Parameter>> conditional = new HashMap<>();
        ReferenceDependencies returnedReference = ReferenceDependencies.NONE;
        Set<ConditionalRead> conditionalReads = new LinkedHashSet<>();
        for (Method target : targets) {
            Summary summary = specialized(target, call, frame, recordInitialization);
            summary.needs.forEach((field, read) -> need(needs, field, read));
            conditionalReads.addAll(summary.conditionalReads);
            if (summary.returnsNormally) {
                returnsNormally = true;
                if (ensures == null) ensures = new HashSet<>(summary.ensures);
                else ensures.retainAll(summary.ensures);
                joinConditional(conditional, summary.conditional);
                returnedReference = returnedReference.join(summary.returnedReference);
            }
            clears.addAll(summary.clears);
            escapes.addAll(summary.escapes);
            parameterEscapes.addAll(summary.parameterEscapes);
            initializedClasses.or(summary.initializedClasses);
            invalidatedNeeds.addAll(summary.invalidatedNeeds);
            summary.exceptionalExits.forEach((type, effect) -> joinExit(exceptional, type, effect));
        }
        conditional.keySet().retainAll(ensures == null ? Set.of() : ensures);
        return new Summary(Map.copyOf(needs), ensures == null ? Set.of() : Set.copyOf(ensures), Set.copyOf(clears), Set.copyOf(escapes), Set.copyOf(parameterEscapes), initializedClasses, Set.copyOf(invalidatedNeeds), returnsNormally, exceptional,
                Map.copyOf(conditional), returnedReference, conditionalReads);
    }

    SourceValue argument(MethodInsnNode call, Frame<SourceValue> frame, int local) {
        int first = frame.getStackSize() - Type.getArgumentTypes(call.desc).length;
        int slot = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
        if (local == 0 && slot == 1) return frame.getStack(first - 1);
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int index = 0; index < arguments.length; index++) {
            if (slot == local) return frame.getStack(first + index);
            slot += arguments[index].getSize();
        }
        return null;
    }

    // A known null path invalidates a binding even when other returns are non-null.
    // External factories remain unknown; this is evidence of null, not a non-null proof.
    boolean nullable(SourceValue value, Set<Method> visiting) {
        if (value == null) return false;
        for (AbstractInsnNode origin : value.insns) {
            if (origin.getOpcode() == Opcodes.ACONST_NULL) return true;
            if (origin instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && referenceFields.contains(field(field)) && !writtenFields.contains(field(field))
                    && !constantReferences.contains(field(field))) return true;
            if (origin instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                Summary initializer = summary(new Method(field(field).owner, "<clinit>", "()V"));
                if (initializer.clears.contains(field(field)) && !initializer.ensures.contains(field(field))) return true;
            }
            if (!(origin instanceof MethodInsnNode call)) continue;
            for (Method callee : callTargets(call, instructionFrames.get(call))) {
                if (!visiting.add(callee)) continue;
                try {
                    MethodNode method = methods.get(callee);
                    Frame<SourceValue>[] frames = sourceFrames.get(callee);
                    if (frames == null) continue;
                    for (int i = 0; i < method.instructions.size(); i++) {
                        if (method.instructions.get(i).getOpcode() != Opcodes.ARETURN || frames[i] == null) continue;
                        SourceValue result = top(frames[i]);
                        Parameter parameter = result.insns.size() == 1 ? parameters.get(result.insns.iterator().next()) : null;
                        if (parameter != null && parameter.method.equals(callee)) {
                            Frame<SourceValue> caller = instructionFrames.get(call);
                            if (caller != null && nullable(argument(call, caller, parameter.local), visiting)) return true;
                        } else if (nullable(result, visiting)) return true;
                    }
                } finally { visiting.remove(callee); }
            }
        }
        return false;
    }

    SourceValue top(Frame<SourceValue> f) {
        return f.getStack(f.getStackSize()-1);
    }
    boolean matches(Method target, Signature signature) {
        return (target.name.equals(signature.name) && target.desc.equals(signature.descriptor))
                || functionalSignatures.getOrDefault(target, Set.of()).contains(signature);
    }
    static void need(Map<Field, String> needs, Field field, String read) {
        needs.merge(field, read, (first, second) -> first.compareTo(second) <= 0 ? first : second);
    }
    void require(Map<Field, String> needs, Set<Field> bound, Collection<Field> fs, String where) {
        for (Field f:fs)if (!bound.contains(f))need(needs, f, where);
    }
    void requireReceiver(Map<Field, String> needs, Set<Field> bound, SourceValue receiver, Set<Value> nonnull,
                         Set<Field> clears, Set<Field> invalidatedNeeds, Map<Field, Set<Parameter>> conditional,
                         Set<ConditionalRead> conditionalReads, Map<AbstractInsnNode, ReferenceSnapshot> references, String where) {
        if (nonnull.contains(Value.of(receiver))) return;
        for (AbstractInsnNode origin : receiver.insns) {
            if (!(origin instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETSTATIC
                    || !referenceFields.contains(field(read))) continue;
            Field field = field(read);
            ReferenceSnapshot snapshot = references.get(origin);
            boolean established = snapshot == null ? bound.contains(field) : snapshot.bound;
            boolean invalidated = snapshot == null ? clears.contains(field) : snapshot.cleared;
            Set<Parameter> parameters = snapshot == null ? conditional.getOrDefault(field, Set.of()) : snapshot.dependencies.parameters;
            if (!established) {
                need(needs, field, where);
                if (invalidated) invalidatedNeeds.add(field);
            } else if (!parameters.isEmpty()) conditionalReads.add(new ConditionalRead(field, where, parameters));
        }
    }

    boolean currentRead(Method method, AbstractInsnNode origin, int guard, Field field) {
        MethodNode body = methods.get(method);
        int source = body.instructions.indexOf(origin);
        if (source < 0 || source >= guard) return false;
        for (int i = source + 1; i < guard; i++) {
            AbstractInsnNode instruction = body.instructions.get(i);
            if (instruction instanceof FieldInsnNode write && write.getOpcode() == Opcodes.PUTSTATIC && field(write).equals(field)) return false;
            if (instruction instanceof MethodInsnNode call) {
                Summary effects = combined(callTargets(call, instructionFrames.get(call)), call, instructionFrames.get(call), false);
                if (effects != null && (effects.clears.contains(field) || effects.ensures.contains(field)
                        || effects.exceptionalExits.values().stream().anyMatch(exit -> exit.clears.contains(field) || exit.bound.contains(field)))) return false;
            }
        }
        return true;
    }
    void callback(Map<Field, String> needs, Set<Field> bound, Set<Field> clears, Set<Escape> escapes, BitSet initializedClasses,
                  Map<Field, Set<Parameter>> conditional, Method root, String registration, Method target, boolean emit) {
        Summary s=summary(target);
        Set<Field> atInvocation = new HashSet<>(bound);
        if (emit) atInvocation.addAll(immutableBindings);
        if (emit && !initializedClasses.get(classId(target.owner))) atInvocation.addAll(entryBindings(target.owner));
        for (var e:s.needs.entrySet()) {
            if (emit && !initializedClasses.get(classId(e.getKey().owner))) atInvocation.addAll(entryBindings(e.getKey().owner));
            if (atInvocation.contains(e.getKey()) && !s.invalidatedNeeds.contains(e.getKey())) {
                Set<Parameter> dependencies = conditional.getOrDefault(e.getKey(), Set.of());
                if (!dependencies.isEmpty()) escapes.add(new Escape(registration, target, e.getKey(), e.getValue(), false, dependencies));
                continue;
            }
            need(needs, e.getKey(), e.getValue());
            escapes.add(new Escape(registration, target, e.getKey(), e.getValue(), clears.contains(e.getKey()) || s.invalidatedNeeds.contains(e.getKey())));
            if (emit)findings.add(new Finding(root, registration, target, e.getKey(), e.getValue()));
        }
    }
    Summary opaque(Method key) {
        MethodNode body = methods.get(key);
        if (body == null) return Summary.empty();
        Set<ParameterEscape> parameters = new LinkedHashSet<>();
        int local = (body.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(key.desc)) {
            if (reference(argument.getDescriptor())) parameters.add(new ParameterEscape(local, null, Set.of(), Set.of(), null));
            local += argument.getSize();
        }
        return new Summary(Map.of(), Set.of(), Set.of(), Set.of(), Set.copyOf(parameters), directEntryClasses(key.owner), Set.of(), true,
                Map.of(new Thrown("java/lang/Throwable", false), new ExitEffects(Set.of(), Set.of(), directEntryClasses(key.owner))), Map.of(), ReferenceDependencies.NONE, Set.of());
    }
    Summary flow(Method k, boolean emit) {
        MethodNode m=methods.get(k);
        Frame<SourceValue>[] ff=sourceFrames.get(k);
        if (ff==null||m.instructions.size()==0)return opaque(k);
        AbstractInsnNode[] ins=m.instructions.toArray();
        Map<AbstractInsnNode, Integer> ix=new IdentityHashMap<>();
        for (int i=0;i<ins.length;i++)ix.put(ins[i], i);
        Map<FlowPoint, State> states = new HashMap<>();
        Deque<FlowPoint> work=new ArrayDeque<>();
        Set<AbstractInsnNode> rethrown = new HashSet<>();
        for (int i = 0; i < ins.length; i++) if (ins[i].getOpcode() == Opcodes.ATHROW && ff[i] != null)
            rethrown.addAll(top(ff[i]).insns);
        // A class initializer is not a global event. Only the entry class and its parents are initialized here.
        ClassNode owner = classes.get(k.owner);
        boolean initializer = k.name.equals("<clinit>");
        Set<Field> entryBindings = emit ? entryBindings(initializer && owner != null ? owner.superName : k.owner) : Set.of();
        String entryOwner = initializer && owner != null ? owner.superName : k.owner;
        BitSet entryClasses = emit ? entryClasses(entryOwner) : directEntryClasses(entryOwner);
        entryClasses.set(classId(k.owner));
        FlowPoint entry = new FlowPoint(0, Map.of());
        states.put(entry, new State(entryBindings, Set.of(), entryClasses, Set.of()));
        work.add(entry);
        Map<Field, String> needs=new LinkedHashMap<>();
        Set<Field> exits = null, normalClears = new HashSet<>(), invalidatedNeeds = new HashSet<>();
        Map<Field, Set<Parameter>> normalConditional = new HashMap<>();
        ReferenceDependencies returnedReference = ReferenceDependencies.NONE;
        Set<ConditionalRead> conditionalReads = new LinkedHashSet<>();
        Map<Thrown, ExitEffects> exceptionalExits = new HashMap<>();
        Set<Escape> escapes=new LinkedHashSet<>();
        Set<ParameterEscape> parameterEscapes = new LinkedHashSet<>();
        BitSet touchedClasses = (BitSet) entryClasses.clone();
        while (!work.isEmpty()) {
            if ((++dataflowSteps & 1023) == 0) progressPoint();
            FlowPoint point = work.remove();
            int i = point.instruction;
            if (ff[i]==null)continue;
            State before = states.get(point);
            Set<Field> bound = new HashSet<>(before.bound), clears = new HashSet<>(before.cleared);
            Set<Value> nonnull = new HashSet<>(before.nonnull);
            Map<Field, Set<Parameter>> conditional = new HashMap<>(before.conditional);
            Map<AbstractInsnNode, ReferenceSnapshot> references = new HashMap<>(before.references);
            BitSet initialized = before.initialized;
            AbstractInsnNode n=ins[i];
            nonnull.removeIf(value -> value.origins.contains(n));
            Frame<SourceValue> f=ff[i];
            int op=n.getOpcode();
            boolean continues = true;
            Map<Thrown, State> thrown = new HashMap<>();
            for (Thrown type : implicitExceptions(n)) thrown.put(type, before);
            String activeClass = activeClass(n);
            if (activeClass != null && !initialized.get(classId(activeClass))) {
                if (emit) bound.addAll(entryBindings(activeClass));
                initialized = union(initialized, emit ? entryClasses(activeClass) : directEntryClasses(activeClass));
                touchedClasses.or(initialized);
            }
            if (n instanceof FieldInsnNode fi) {
                if (op == Opcodes.GETSTATIC && reference(fi.desc)) {
                    Field field = field(fi);
                    boolean nil = !bound.contains(field) && (clears.contains(field)
                            || nullable(new SourceValue(1, n), new HashSet<>()));
                    references.put(n, new ReferenceSnapshot(new ReferenceDependencies(nil, conditional.getOrDefault(field, Set.of())),
                            bound.contains(field), !bound.contains(field) && clears.contains(field)));
                }
                if (op==Opcodes.PUTSTATIC && referenceFields.contains(field(fi))) {
                    SourceValue v=top(f);
                    ReferenceDependencies value = referenceDependencies(v, before);
                    boolean nil = value.nullable && !nonnull.contains(Value.of(v));
                    conditional.remove(field(fi));
                    if (nil){
                        bound.remove(field(fi));
                        clears.add(field(fi));
                    } else {
                        bound.add(field(fi));
                        if (!value.parameters.isEmpty() && !nonnull.contains(Value.of(v))) conditional.put(field(fi), value.parameters);
                    }
                }
                if (op==Opcodes.GETFIELD)requireReceiver(needs, bound, top(f), nonnull, clears, invalidatedNeeds, conditional, conditionalReads, references, site(k, i));
                if (op==Opcodes.PUTFIELD)requireReceiver(needs, bound, f.getStack(f.getStackSize()-2), nonnull, clears, invalidatedNeeds, conditional, conditionalReads, references, site(k, i));
            }
            if (n instanceof MethodInsnNode call) {
                Type[] args=Type.getArgumentTypes(call.desc);
                int first=f.getStackSize()-args.length;
                if (op!=Opcodes.INVOKESTATIC)requireReceiver(needs, bound, f.getStack(first-1), nonnull, clears, invalidatedNeeds, conditional, conditionalReads, references, site(k, i));
                if (op == Opcodes.INVOKEINTERFACE || op == Opcodes.INVOKEVIRTUAL) {
                    // A call through a formal receiver executes the selected method synchronously.
                    // Keep its signature until a caller supplies the concrete callback value.
                    for (AbstractInsnNode origin : f.getStack(first - 1).insns) {
                        Parameter parameter = parameters.get(origin);
                        if (parameter != null && parameter.method.equals(k)) {
                            if (contextValues.containsKey(parameter)) {
                                Signature signature = new Signature(call.name, call.desc);
                                for (Method target : contextValues.get(parameter)) if (matches(target, signature))
                                    callback(needs, bound, clears, escapes, initialized, conditional, k, site(k, i), target, false);
                            } else parameterEscapes.add(new ParameterEscape(parameter.local, site(k, i),
                                    Set.copyOf(bound), Set.copyOf(clears), new Signature(call.name, call.desc), Map.copyOf(conditional)));
                        }
                    }
                }
                Summary s = combined(callTargets(call, f), call, f);
                if (s!=null) {
                    State callState = new State(bound, nonnull, initialized, clears, before.caught, conditional, references);
                    s.exceptionalExits.forEach((type, effects) -> thrown.merge(type,
                            apply(callState, substitute(effects, call, f, callState)), CallbackInitialization::joinState));
                    continues = s.returnsNormally;
                    for (var e : s.needs.entrySet()) {
                        if (s.invalidatedNeeds.contains(e.getKey())) {
                            need(needs, e.getKey(), e.getValue());
                            invalidatedNeeds.add(e.getKey());
                        } else if (!bound.contains(e.getKey())) {
                            need(needs, e.getKey(), e.getValue());
                            if (clears.contains(e.getKey())) invalidatedNeeds.add(e.getKey());
                        }
                    }
                    for (ConditionalRead read : s.conditionalReads) {
                        ReferenceDependencies value = substitute(new ReferenceDependencies(false, read.parameters), call, f, callState);
                        if (value.nullable) {
                            need(needs, read.dependency, read.read);
                            invalidatedNeeds.add(read.dependency);
                        }
                        if (!value.parameters.isEmpty())
                            conditionalReads.add(new ConditionalRead(read.dependency, read.read, value.parameters));
                    }
                    for (Escape e:s.escapes) {
                        if (!e.conditional.isEmpty()) {
                            ReferenceDependencies value = substitute(new ReferenceDependencies(false, e.conditional), call, f, callState);
                            if (value.nullable) {
                                escapes.add(new Escape(e.registration, e.callback, e.dependency, e.read, true));
                                if (emit) findings.add(new Finding(k, e.registration, e.callback, e.dependency, e.read));
                            } else if (!value.parameters.isEmpty()) {
                                escapes.add(new Escape(e.registration, e.callback, e.dependency, e.read, false, value.parameters));
                            }
                        } else if (e.cleared || (!bound.contains(e.dependency) && !(emit && immutableBindings.contains(e.dependency)))) {
                            escapes.add(e);
                            if (emit)findings.add(new Finding(k, e.registration, e.callback, e.dependency, e.read));
                        }
                    }
                    for (ParameterEscape effect : s.parameterEscapes) {
                        SourceValue actual = argument(call, f, effect.local);
                        if (actual == null) continue;
                        String registration = effect.registration == null ? site(k, i) : effect.registration;
                        State atExposure = apply(callState, substitute(new ExitEffects(effect.bound, effect.clears,
                                new BitSet(), effect.conditional), call, f, callState));
                        for (Method target : targets(actual)) {
                            if (effect.invocation != null && !matches(target, effect.invocation)) continue;
                            callback(needs, atExposure.bound, atExposure.cleared, escapes, initialized, atExposure.conditional, k, registration, target, emit);
                        }
                        for (AbstractInsnNode origin : actual.insns) {
                            Parameter parameter = parameters.get(origin);
                            if (parameter != null && parameter.method.equals(k)) {
                                parameterEscapes.add(new ParameterEscape(parameter.local, registration,
                                        atExposure.bound, atExposure.cleared, effect.invocation, atExposure.conditional));
                            }
                        }
                    }
                    ExitEffects effects = substitute(new ExitEffects(s.ensures, s.clears, s.initializedClasses, s.conditional), call, f, callState);
                    bound.removeAll(effects.clears);
                    clears.addAll(effects.clears);
                    bound.addAll(effects.bound);
                    for (Field field : effects.clears) conditional.remove(field);
                    for (Field field : effects.bound) conditional.remove(field);
                    conditional.putAll(effects.conditional);
                    if (reference(Type.getReturnType(call.desc).getDescriptor()))
                        references.put(n, new ReferenceSnapshot(substitute(s.returnedReference, call, f, callState), false, false));
                    if (emit) {
                        initialized = union(initialized, s.initializedClasses);
                        touchedClasses.or(s.initializedClasses);
                    }
                } else {
                    thrown.put(new Thrown("java/lang/Throwable", false), new State(bound, nonnull, initialized, clears, before.caught, conditional, references));
                    if (reference(Type.getReturnType(call.desc).getDescriptor())) references.put(n, new ReferenceSnapshot(ReferenceDependencies.NONE, false, false));
                    for (int a=first;a<f.getStackSize();a++) {
                        SourceValue actual = f.getStack(a);
                        for (Method target : targets(actual)) callback(needs, bound, clears, escapes, initialized, conditional, k, site(k, i), target, emit);
                        for (AbstractInsnNode origin : actual.insns) {
                            Parameter parameter = parameters.get(origin);
                            if (parameter != null && parameter.method.equals(k)) {
                                parameterEscapes.add(new ParameterEscape(parameter.local, site(k, i), Set.copyOf(bound), Set.copyOf(clears), null, Map.copyOf(conditional)));
                            }
                        }
                    }
                }
            }
            if (op == Opcodes.ARRAYLENGTH || op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) {
                requireReceiver(needs, bound, top(f), nonnull, clears, invalidatedNeeds, conditional, conditionalReads, references, site(k, i));
            }
            if (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) {
                requireReceiver(needs, bound, f.getStack(f.getStackSize() - 2), nonnull, clears, invalidatedNeeds, conditional, conditionalReads, references, site(k, i));
            }
            if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
                requireReceiver(needs, bound, f.getStack(f.getStackSize() - 3), nonnull, clears, invalidatedNeeds, conditional, conditionalReads, references, site(k, i));
            }
            if (op>=Opcodes.IRETURN&&op<=Opcodes.RETURN) {
                if (exits==null)exits=new HashSet<>(bound);
                else exits.retainAll(bound);
                normalClears.addAll(clears);
                joinConditional(normalConditional, conditional);
                if (op == Opcodes.ARETURN) returnedReference = returnedReference.join(referenceDependencies(top(f), before));
            }
            if (op == Opcodes.ATHROW) {
                State throwState = new State(bound, nonnull, initialized, clears, before.caught, conditional, references);
                for (Thrown type : thrownTypes(k, top(f), before)) thrown.merge(type, throwState, CallbackInitialization::joinState);
            }
            List<Integer> next=new ArrayList<>();
            Map<Integer, Set<Field>> branch=new HashMap<>();
            Map<Integer, Set<Value>> branchValues = new HashMap<>();
            if (n instanceof JumpInsnNode j) {
                next.add(ix.get(j.label));
                if (op!=Opcodes.GOTO&&i+1<ins.length)next.add(i+1);
                if (op==Opcodes.IFNULL||op==Opcodes.IFNONNULL) {
                    int nonnullBranch=op==Opcodes.IFNONNULL?ix.get(j.label):i+1;
                    Set<Field> guarded=new HashSet<>(bound);
                    Set<Field> checked = origins(top(f));
                    if (checked.size() == 1 && top(f).insns.size() == 1
                            && currentRead(k, top(f).insns.iterator().next(), i, checked.iterator().next())) guarded.addAll(checked);
                    branch.put(nonnullBranch, guarded);
                    Set<Value> values = new HashSet<>(nonnull);
                    values.add(Value.of(top(f)));
                    branchValues.put(nonnullBranch, values);
                }
            } else if (n instanceof TableSwitchInsnNode s) {
                next.add(ix.get(s.dflt));
                for (LabelNode l:s.labels)next.add(ix.get(l));
            } else if (n instanceof LookupSwitchInsnNode s) {
                next.add(ix.get(s.dflt));
                for (LabelNode l:s.labels)next.add(ix.get(l));
            } else if (!(op>=Opcodes.IRETURN&&op<=Opcodes.RETURN)&&op!=Opcodes.ATHROW&&i+1<ins.length)next.add(i+1);
            if (continues) for (int dest:next)merge(states, work, dest, new State(branch.getOrDefault(dest, bound), branchValues.getOrDefault(dest, nonnull), initialized, clears, before.caught, conditional, references));
            for (var exceptional : thrown.entrySet()) {
                Thrown type = exceptional.getKey();
                State state = exceptional.getValue();
                boolean handled = false;
                for (TryCatchBlockNode handler : m.tryCatchBlocks) {
                    if (i < ix.get(handler.start) || i >= ix.get(handler.end)) continue;
                    Coverage match = catches(type, handler.type);
                    if (match == Coverage.DISJOINT) continue;
                    Map<AbstractInsnNode, Set<Thrown>> caught = new HashMap<>(state.caught);
                    Thrown caughtType = !type.exact && handler.type != null && exceptionSubtype(handler.type, type.type) == Relation.YES
                            ? new Thrown(handler.type, false) : type;
                    AbstractInsnNode marker = exceptionMarkers.get(handler);
                    if (rethrown.contains(marker)) caught.put(marker, Set.of(caughtType));
                    merge(states, work, ix.get(handler.handler), new State(state.bound, state.nonnull, state.initialized, state.cleared, caught, state.conditional, state.references));
                    if (match == Coverage.COVERS) { handled = true; break; }
                }
                if (!handled) joinExit(exceptionalExits, type, new ExitEffects(state.bound, state.cleared, state.initialized, state.conditional));
            }
        }
        normalConditional.keySet().retainAll(exits == null ? Set.of() : exits);
        return new Summary(Map.copyOf(needs), exits==null?Set.of():Set.copyOf(exits), Set.copyOf(normalClears), Set.copyOf(escapes), Set.copyOf(parameterEscapes), touchedClasses, Set.copyOf(invalidatedNeeds), exits != null, exceptionalExits,
                Map.copyOf(normalConditional), returnedReference, conditionalReads);
    }
    static State apply(State state, ExitEffects effect) {
        Set<Field> bound = new HashSet<>(state.bound), cleared = new HashSet<>(state.cleared);
        Map<Field, Set<Parameter>> conditional = new HashMap<>(state.conditional);
        bound.removeAll(effect.clears);
        bound.addAll(effect.bound);
        cleared.addAll(effect.clears);
        effect.clears.forEach(conditional::remove);
        effect.bound.forEach(conditional::remove);
        conditional.putAll(effect.conditional);
        return new State(bound, state.nonnull, union(state.initialized, effect.initialized), cleared, state.caught, conditional, state.references);
    }

    ReferenceDependencies referenceDependencies(SourceValue value, State state) {
        if (value == null || state.nonnull.contains(Value.of(value))) return ReferenceDependencies.NONE;
        ReferenceDependencies result = ReferenceDependencies.NONE;
        for (AbstractInsnNode origin : value.insns) {
            Parameter parameter = parameters.get(origin);
            if (parameter != null) result = result.join(new ReferenceDependencies(false, Set.of(parameter)));
            else if (state.references.containsKey(origin)) result = result.join(state.references.get(origin).dependencies);
            else if (!(origin instanceof MethodInsnNode) && !(origin instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC))
                result = result.join(new ReferenceDependencies(nullable(new SourceValue(value.size, origin), new HashSet<>()), Set.of()));
        }
        return result;
    }

    ReferenceDependencies substitute(ReferenceDependencies value, MethodInsnNode call, Frame<SourceValue> frame, State state) {
        ReferenceDependencies resolved = new ReferenceDependencies(value.nullable, Set.of());
        for (Parameter parameter : value.parameters)
            resolved = resolved.join(referenceDependencies(argument(call, frame, parameter.local), state));
        return resolved;
    }

    ExitEffects substitute(ExitEffects effect, MethodInsnNode call, Frame<SourceValue> frame, State state) {
        Set<Field> bound = new HashSet<>(effect.bound), clears = new HashSet<>(effect.clears);
        Map<Field, Set<Parameter>> conditional = new HashMap<>();
        effect.conditional.forEach((field, parameters) -> {
            ReferenceDependencies value = substitute(new ReferenceDependencies(false, parameters), call, frame, state);
            if (value.nullable) { bound.remove(field); clears.add(field); }
            else if (!value.parameters.isEmpty()) conditional.put(field, value.parameters);
        });
        return new ExitEffects(bound, clears, effect.initialized, conditional);
    }

    static void joinConditional(Map<Field, Set<Parameter>> target, Map<Field, Set<Parameter>> added) {
        added.forEach((field, parameters) -> target.merge(field, parameters, (first, second) -> {
            Set<Parameter> joined = new HashSet<>(first); joined.addAll(second); return Set.copyOf(joined);
        }));
    }

    static void joinExit(Map<Thrown, ExitEffects> exits, Thrown type, ExitEffects incoming) {
        exits.merge(type, incoming, (old, added) -> {
            Set<Field> bound = new HashSet<>(old.bound), clears = new HashSet<>(old.clears);
            bound.retainAll(added.bound);
            clears.addAll(added.clears);
            Map<Field, Set<Parameter>> conditional = new HashMap<>(old.conditional);
            joinConditional(conditional, added.conditional);
            conditional.keySet().retainAll(bound);
            return new ExitEffects(bound, clears, union(old.initialized, added.initialized), conditional);
        });
    }

    Coverage catches(Thrown thrown, String handler) {
        if (handler == null || handler.equals("java/lang/Throwable")) return Coverage.COVERS;
        Relation subtype = exceptionSubtype(thrown.type, handler);
        if (subtype == Relation.YES) return Coverage.COVERS;
        if (thrown.exact) return subtype == Relation.NO ? Coverage.DISJOINT : Coverage.POSSIBLE;
        Relation reverse = exceptionSubtype(handler, thrown.type);
        return subtype == Relation.NO && reverse == Relation.NO ? Coverage.DISJOINT : Coverage.POSSIBLE;
    }

    Relation exceptionSubtype(String type, String parent) {
        Set<String> visited = new HashSet<>();
        while (type != null && visited.add(type)) {
            if (type.equals(parent)) return Relation.YES;
            if (type.equals("java/lang/Object")) return Relation.NO;
            ClassNode owned = classes.get(type);
            if (owned != null) { type = owned.superName; continue; }
            if (!exceptionParents.containsKey(type) && !missingExceptionTypes.contains(type)) {
                // Platform headers are read without loading or initializing consumer code.
                try (var stream = ClassLoader.getPlatformClassLoader().getResourceAsStream(type + ".class")) {
                    if (stream == null) missingExceptionTypes.add(type);
                    else exceptionParents.put(type, new ClassReader(stream).getSuperName());
                } catch (java.io.IOException | IllegalArgumentException | SecurityException unavailable) { missingExceptionTypes.add(type); }
            }
            if (missingExceptionTypes.contains(type)) return Relation.UNKNOWN;
            type = exceptionParents.get(type);
        }
        return type == null ? Relation.NO : Relation.UNKNOWN;
    }

    Set<Thrown> thrownTypes(Method method, SourceValue value, State state) {
        Set<Thrown> types = new HashSet<>();
        if (value == null || value.insns.isEmpty()) return Set.of(new Thrown("java/lang/Throwable", false));
        for (AbstractInsnNode origin : value.insns) {
            if (state.caught.containsKey(origin)) { types.addAll(state.caught.get(origin)); continue; }
            if (exceptionTypes.containsKey(origin)) continue;
            if (origin.getOpcode() == Opcodes.ACONST_NULL) {
                types.add(new Thrown("java/lang/NullPointerException", true)); continue;
            }
            if (origin instanceof TypeInsnNode allocation && origin.getOpcode() == Opcodes.NEW) {
                types.add(new Thrown(allocation.desc, true)); continue;
            }
            String descriptor = null;
            if (origin instanceof FieldInsnNode field) descriptor = field.desc;
            if (origin instanceof MethodInsnNode call) descriptor = Type.getReturnType(call.desc).getDescriptor();
            Parameter parameter = parameters.get(origin);
            if (parameter != null && parameter.method.equals(method)) {
                int local = (methods.get(method).access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                for (Type argument : Type.getArgumentTypes(method.desc)) {
                    if (local == parameter.local) { descriptor = argument.getDescriptor(); break; }
                    local += argument.getSize();
                }
            }
            String type = exceptionTypes.get(origin);
            if (type == null && descriptor != null && descriptor.startsWith("L")) type = Type.getType(descriptor).getInternalName();
            types.add(new Thrown(type == null ? "java/lang/Throwable" : type, false));
            if (!exceptionTypes.containsKey(origin)) types.add(new Thrown("java/lang/NullPointerException", true));
        }
        return types;
    }

    static Set<Thrown> implicitExceptions(AbstractInsnNode instruction) {
        int op = instruction.getOpcode();
        Set<Thrown> types = new HashSet<>();
        if (instruction instanceof MethodInsnNode || instruction instanceof FieldInsnNode
                || instruction instanceof TypeInsnNode || instruction instanceof InvokeDynamicInsnNode
                || op == Opcodes.LDC || op == Opcodes.MULTIANEWARRAY) types.add(new Thrown("java/lang/Error", false));
        if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE || op == Opcodes.INVOKESPECIAL
                || op == Opcodes.GETFIELD || op == Opcodes.PUTFIELD || op == Opcodes.ARRAYLENGTH
                || op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT
                || (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) || (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE))
            types.add(new Thrown("java/lang/NullPointerException", true));
        if ((op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) || (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE))
            types.add(new Thrown("java/lang/ArrayIndexOutOfBoundsException", true));
        if (op == Opcodes.AASTORE) types.add(new Thrown("java/lang/ArrayStoreException", true));
        if (op == Opcodes.IDIV || op == Opcodes.LDIV || op == Opcodes.IREM || op == Opcodes.LREM)
            types.add(new Thrown("java/lang/ArithmeticException", true));
        if (op == Opcodes.CHECKCAST) types.add(new Thrown("java/lang/ClassCastException", true));
        if (op == Opcodes.NEWARRAY || op == Opcodes.ANEWARRAY || op == Opcodes.MULTIANEWARRAY)
            types.add(new Thrown("java/lang/NegativeArraySizeException", true));
        if (op == Opcodes.MONITOREXIT) types.add(new Thrown("java/lang/IllegalMonitorStateException", true));
        if (op == Opcodes.INVOKEDYNAMIC) types.add(new Thrown("java/lang/Throwable", false));
        return types;
    }

    static BitSet union(BitSet current, BitSet added) {
        BitSet result = (BitSet) current.clone();
        result.or(added);
        return result.equals(current) ? current : result;
    }
    static void merge(Map<FlowPoint, State> states, Deque<FlowPoint> work, int dest, State incoming) {
        FlowPoint point = new FlowPoint(dest, incoming.caught);
        State old = states.get(point);
        if (old == null) {
            states.put(point, incoming);
            work.add(point);
            return;
        }
        State joined = joinState(old, incoming);
        if (!joined.equals(old)) {
            states.put(point, joined);
            work.add(point);
        }
    }
    static State joinState(State old, State incoming) {
        if (old.equals(incoming)) return old;
        Set<Field> bound = new HashSet<>(old.bound);
        bound.retainAll(incoming.bound);
        Set<Value> values = new HashSet<>(old.nonnull);
        values.retainAll(incoming.nonnull);
        BitSet initialized = union(old.initialized, incoming.initialized);
        Set<Field> cleared = new HashSet<>(old.cleared);
        cleared.addAll(incoming.cleared);
        Map<AbstractInsnNode, Set<Thrown>> caught = new HashMap<>(old.caught);
        incoming.caught.forEach((origin, types) -> caught.merge(origin, types, (first, second) -> {
            Set<Thrown> joined = new HashSet<>(first); joined.addAll(second); return Set.copyOf(joined);
        }));
        Map<Field, Set<Parameter>> conditional = new HashMap<>(old.conditional);
        joinConditional(conditional, incoming.conditional);
        conditional.keySet().retainAll(bound);
        Map<AbstractInsnNode, ReferenceSnapshot> references = new HashMap<>(old.references);
        incoming.references.forEach((origin, value) -> references.merge(origin, value, ReferenceSnapshot::join));
        return new State(bound, values, initialized, cleared, caught, conditional, references);
    }
    List<Method> initialOrder() {
        record Visit(Method method, boolean exit) {}
        Comparator<Method> order = Comparator.comparing(Method::owner).thenComparing(Method::name).thenComparing(Method::desc);
        Set<Method> seen = new HashSet<>();
        List<Method> result = new ArrayList<>();
        for (Method root : methods.keySet().stream().sorted(order).toList()) {
            Deque<Visit> work = new ArrayDeque<>();
            work.push(new Visit(root, false));
            while (!work.isEmpty()) {
                progressPoint();
                Visit visit = work.pop();
                if (visit.exit) { result.add(visit.method); continue; }
                if (!seen.add(visit.method)) continue;
                work.push(new Visit(visit.method, true));
                Set<Method> prerequisites = new java.util.TreeSet<>(order.reversed());
                prerequisites.add(new Method(visit.method.owner, "<clinit>", "()V"));
                MethodNode body = methods.get(visit.method);
                Frame<SourceValue>[] frames = sourceFrames.get(visit.method);
                if (frames != null) for (int i = 0; i < body.instructions.size(); i++) {
                    if (frames[i] == null) continue;
                    AbstractInsnNode instruction = body.instructions.get(i);
                    if (instruction instanceof MethodInsnNode call) prerequisites.addAll(callTargets(call, frames[i]));
                    if (instruction instanceof InvokeDynamicInsnNode || instruction.getOpcode() == Opcodes.NEW)
                        prerequisites.addAll(targets(new SourceValue(1, instruction)));
                    String active = activeClass(instruction);
                    if (active != null) prerequisites.add(new Method(active, "<clinit>", "()V"));
                }
                for (Method prerequisite : prerequisites) if (methods.containsKey(prerequisite) && !seen.contains(prerequisite))
                    work.push(new Visit(prerequisite, false));
            }
        }
        return result;
    }

    void analyze() {
        for (ClassNode candidate : classes.values()) {
            if ((candidate.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) continue;
            for (String parent : lineage(candidate.name))
                concreteSubtypes.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(candidate.name);
        }
        for (Method k:methods.keySet()){
            returned.put(k, new LinkedHashSet<>());
            summaries.put(k, Summary.empty());
        }
        for (MethodNode method : methods.values()) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode lambda)
                        || !lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        || lambda.bsmArgs.length == 0 || !(lambda.bsmArgs[0] instanceof Type signature)) continue;
                for (Method target : targets(new SourceValue(1, lambda))) {
                    functionalSignatures.computeIfAbsent(target, ignored -> new HashSet<>())
                            .add(new Signature(lambda.name, signature.getDescriptor()));
                }
            }
        }
        phase = "dependency ordering"; progressStarted = false;
        initialOrder().forEach(this::enqueue);
        phase = "behavior summaries"; progressStarted = false;
        while (!pendingSummaries.isEmpty()) {
            ++summaryEvaluations;
            progressPoint();
            Object node = pendingSummaries.removeFirst();
            queuedSummaries.remove(node);
            evaluating = node;
            Context context = node instanceof Context value ? value : null;
            Method key = context == null ? (Method) node : context.method;
            contextValues.clear();
            currentInitializationCalls = new LinkedHashSet<>();
            if (context != null) contextValues.putAll(context.arguments);
            callTargetCache.clear();

            Frame<SourceValue>[] frames = sourceFrames.get(key);
            MethodNode body = methods.get(key);
            if (frames != null) for (int i = 0; i < body.instructions.size(); i++) {
                if (frames[i] == null) continue;
                AbstractInsnNode instruction = body.instructions.get(i);
                if (instruction.getOpcode() == Opcodes.ARETURN
                        && returned.get(key).addAll(targets(top(frames[i])))) changed(new ReturnedCallbacks(key));
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && fieldCallbacks.computeIfAbsent(field(field), ignored -> new LinkedHashSet<>())
                            .addAll(targets(top(frames[i])))) changed(field(field));
            }
            Summary value = flow(key, false);
            Summary previous = context == null ? summaries.put(key, value) : contextSummaries.put(context, value);
            initializationCalls.put(node, Set.copyOf(currentInitializationCalls));
            if (!sameBehavior(value, previous)) {
                long updates = summaryUpdates.merge(node, 1L, Long::sum);
                if (updates > mostUpdates) { mostUpdates = updates; hottestSummary = node; }
                changed(node);
            }
            if (context == null && key.name.equals("<clinit>")) {
                Set<Field> previousBindings = classInitializationBindings.put(key.owner, value.ensures);
                if (!value.ensures.equals(previousBindings)) changed(key.owner);
            }
        }
        evaluating = null;
        contextValues.clear();
        callTargetCache.clear();

        phase = "initialization reachability"; progressStarted = false;
        solveInitialization();
        phase = "entry roots"; progressStarted = false;
        immutableBindings.addAll(constantReferences);
        for (var e : summaries.entrySet()) {
            Summary summary = e.getValue();
            if (!e.getKey().name.equals("<clinit>") || !summary.parameterEscapes.isEmpty()) continue;
            for (Field field : summary.ensures) {
                if (field.owner.equals(e.getKey().owner) && finalReferences.contains(field)
                        && !summary.needs.containsKey(field)
                        && summary.escapes.stream().noneMatch(escape -> escape.dependency.equals(field))) {
                    immutableBindings.add(field);
                }
            }
        }
        for (var e:methods.entrySet())if (((e.getValue().access & Opcodes.ACC_PUBLIC) != 0)
                || (e.getKey().name.equals("<clinit>") && !summaries.get(e.getKey()).escapes.isEmpty())) {
            entryRoots.add(e.getKey());
            progressPoint();
            flow(e.getKey(), true);
        }
    }
    record Result(int classes, int methods, int analyzedBodies, List<Method> roots, List<Finding> findings, List<String> analysisErrors,
                  long summaryEvaluations, int callbackContexts, long dataflowSteps, long initializationPropagations, List<Dependency> dependencies) {
        int dependencyEdges() { return dependencies.size(); }
    }

    static boolean sameBehavior(Summary first, Summary second) {
        return second != null && first.needs.equals(second.needs) && first.ensures.equals(second.ensures)
                && first.clears.equals(second.clears) && first.escapes.equals(second.escapes)
                && first.parameterEscapes.equals(second.parameterEscapes) && first.invalidatedNeeds.equals(second.invalidatedNeeds)
                && first.returnsNormally == second.returnsNormally && first.exceptionalExits.equals(second.exceptionalExits)
                && first.conditional.equals(second.conditional) && first.returnedReference.equals(second.returnedReference)
                && first.conditionalReads.equals(second.conditionalReads);
    }
    void solveInitialization() {
        Map<Object, Summary> nodes = new LinkedHashMap<>(summaries);
        nodes.putAll(contextSummaries);
        String[] classNames = new String[classIds.size()];
        classIds.forEach((name, id) -> classNames[id] = name);
        Map<Object, Set<Object>> users = new HashMap<>();
        for (var node : nodes.entrySet()) {
            initializationReachability.put(node.getKey(), (BitSet) node.getValue().initializedClasses.clone());
            Set<Object> prerequisites = new LinkedHashSet<>(initializationCalls.getOrDefault(node.getKey(), Set.of()));
            BitSet direct = node.getValue().initializedClasses;
            for (int id = direct.nextSetBit(0); id >= 0; id = direct.nextSetBit(id + 1)) {
                Method initializer = new Method(classNames[id], "<clinit>", "()V");
                if (nodes.containsKey(initializer)) prerequisites.add(initializer);
            }
            initializationCalls.put(node.getKey(), Set.copyOf(prerequisites));
            for (Object prerequisite : prerequisites)
                users.computeIfAbsent(prerequisite, ignored -> new LinkedHashSet<>()).add(node.getKey());
        }
        Deque<Object> work = new ArrayDeque<>(nodes.keySet());
        Set<Object> queued = new HashSet<>(nodes.keySet());
        while (!work.isEmpty()) {
            initializationPending = work.size();
            progressPoint();
            Object prerequisite = work.removeFirst(); queued.remove(prerequisite);
            BitSet facts = initializationReachability.get(prerequisite);
            for (Object user : users.getOrDefault(prerequisite, Set.of())) {
                BitSet previous = initializationReachability.get(user);
                BitSet joined = union(previous, facts);
                if (!joined.equals(previous)) {
                    initializationReachability.put(user, joined);
                    initializationPropagations++;
                    if (queued.add(user)) work.addLast(user);
                }
            }
        }
        initializationPending = 0;
    }

    List<Dependency> dependencyGraph() {
        List<Dependency> edges = new ArrayList<>();
        dependents.forEach((fact, users) -> {
            String type = fact instanceof Method ? "method-summary" : fact instanceof ReturnedCallbacks ? "returned-callbacks"
                    : fact instanceof Field ? "field-callbacks" : fact instanceof Context ? "callback-context" : "class-initialization";
            String name = fact instanceof ReturnedCallbacks value ? value.method.toString()
                    : fact instanceof Field field ? field + ":" + field.desc : fact.toString();
            users.forEach(user -> edges.add(new Dependency(type, name,
                    user instanceof Context context ? context.method : (Method) user,
                    user instanceof Context ? user.toString() : null)));
        });
        initializationCalls.forEach((user, prerequisites) -> prerequisites.forEach(prerequisite -> edges.add(
                new Dependency("initialization-reachability", prerequisite.toString(),
                        user instanceof Context context ? context.method : (Method) user,
                        user instanceof Context ? user.toString() : null))));
        return edges.stream().sorted(Comparator.comparing(Dependency::factType).thenComparing(Dependency::fact)
                .thenComparing(edge -> edge.dependent.toString())
                .thenComparing(edge -> edge.dependentContext == null ? "" : edge.dependentContext)).toList();
    }

    Set<Field> entryBindings(String owner) {
        Set<Field> bindings = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (String type = owner; type != null && seen.add(type); ) {
            dependOn(type);
            bindings.addAll(classInitializationBindings.getOrDefault(type, Set.of()));
            for (Field constant : constantReferences) if (constant.owner.equals(type)) bindings.add(constant);
            ClassNode node = classes.get(type);
            type = node == null ? null : node.superName;
        }
        return bindings;
    }

    BitSet directEntryClasses(String owner) {
        BitSet initialized = new BitSet();
        Set<String> parents = new HashSet<>();
        for (String type = owner; type != null && parents.add(type); ) {
            initialized.set(classId(type));
            ClassNode node = classes.get(type);
            type = node == null ? null : node.superName;
        }
        return initialized;
    }
    BitSet entryClasses(String owner) {
        BitSet initialized = directEntryClasses(owner);
        Set<String> parents = new HashSet<>();
        for (String type = owner; type != null && parents.add(type); ) {
            BitSet initializer = initializationReachability.get(new Method(type, "<clinit>", "()V"));
            if (initializer != null) initialized.or(initializer);
            ClassNode node = classes.get(type);
            type = node == null ? null : node.superName;
        }
        return initialized;
    }
    void readClass(byte[] bytes, String location) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            if (classes.putIfAbsent(node.name, node) != null) {
                analysisErrors.add(location + ": duplicate class " + node.name);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            analysisErrors.add(location + ": unreadable class (" + exception.getClass().getSimpleName() + ")");
        }
    }

    static Result inspect(List<Path> roots) throws Exception {
        return inspect(roots, Runtime.version().feature());
    }

    static Result inspect(List<Path> roots, int targetJava) throws Exception {
        return inspect(roots, targetJava, null);
    }
    static Result inspect(List<Path> roots, int targetJava, java.util.function.Consumer<Progress> progress) throws Exception {
        CallbackInitialization analysis = new CallbackInitialization();
        analysis.progress = progress;
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (var walk = Files.walk(root)) {
                    for (Path file : walk.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                        analysis.readClass(Files.readAllBytes(file), root.relativize(file).toString());
                    }
                }
            } else if (Files.isRegularFile(root)) {
                try (JarFile archive = new JarFile(root.toFile(), false, ZipFile.OPEN_READ,
                        Runtime.Version.parse(Integer.toString(Math.max(8, targetJava))))) {
                    for (var entry : archive.versionedStream().sorted(Comparator.comparing(java.util.jar.JarEntry::getName)).toList()) {
                        if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/versions/")) continue;
                        try (var stream = archive.getInputStream(entry)) {
                            analysis.readClass(stream.readAllBytes(), entry.getName());
                        }
                    }
                }
            }
        }
        analysis.prepare();
        analysis.analyze();
        var findings = new LinkedHashSet<>(analysis.findings).stream()
                .sorted(Comparator.comparing((Finding f) -> f.root().toString()).thenComparing(Finding::registration)
                        .thenComparing(f -> f.dependency().toString()).thenComparing(Finding::read)).toList();
        return new Result(analysis.classes.size(), analysis.methods.size(), analysis.sourceFrames.size(),
                analysis.entryRoots.stream().sorted(Comparator.comparing(Method::toString)).toList(), findings,
                analysis.analysisErrors.stream().distinct().sorted().toList(), analysis.summaryEvaluations,
                analysis.contextSummaries.size(), analysis.dataflowSteps, analysis.initializationPropagations, analysis.dependencyGraph());
    }
}
