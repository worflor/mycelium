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
    record Escape(String registration, Method callback, Field dependency, String read, boolean cleared) {
    }
    record ParameterEscape(int local, String registration, Set<Field> bound, Set<Field> clears,
                           Signature invocation) {}
    record Summary(Map<Field, String> needs, Set<Field> ensures, Set<Field> clears, Set<Escape> escapes,
                   Set<ParameterEscape> parameterEscapes, Set<String> initializedClasses, Set<Field> invalidatedNeeds) {
        static Summary empty() {
            return new Summary(Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }
    }
    record Finding(Method root, String registration, Method callback, Field dependency, String read) {
    }
    record Parameter(Method method, int local) {}
    record ReturnedCallbacks(Method method) {}
    record Dependency(String factType, String fact, Method dependent) {}
    record Signature(String name, String descriptor) {}
    record Value(Set<AbstractInsnNode> origins) {
        static Value of(SourceValue value) { return new Value(Set.copyOf(value.insns)); }
    }
    record State(Set<Field> bound, Set<Value> nonnull, Set<String> initialized, Set<Field> cleared) {
        State {
            bound = Set.copyOf(bound);
            nonnull = Set.copyOf(nonnull);
            initialized = Set.copyOf(initialized);
            cleared = Set.copyOf(cleared);
        }
    }
    final Map<String, ClassNode> classes = new TreeMap<>();
    final Map<Method, MethodNode> methods = new LinkedHashMap<>();
    final Map<Method, Frame<SourceValue>[]> sourceFrames = new HashMap<>();
    final Set<Field> referenceFields = new HashSet<>();
    final Map<Method, Set<Method>> returned = new HashMap<>();
    final Map<Method, Summary> summaries = new HashMap<>();
    final List<String> analysisErrors = new ArrayList<>();
    final Map<String, Set<Field>> classInitializationBindings = new HashMap<>();
    final List<Finding> findings = new ArrayList<>();
    final Set<Method> entryRoots = new LinkedHashSet<>();
    final Map<AbstractInsnNode, Parameter> parameters = new IdentityHashMap<>();
    final Map<AbstractInsnNode, Frame<SourceValue>> instructionFrames = new IdentityHashMap<>();
    final Map<Field, Set<Method>> fieldCallbacks = new HashMap<>();
    final Set<Field> writtenFields = new HashSet<>();
    final Set<Field> constantReferences = new HashSet<>();
    final Set<Field> finalReferences = new HashSet<>();
    final Set<Field> immutableBindings = new HashSet<>();
    final Map<String, Set<String>> lineageCache = new HashMap<>();
    final Map<MethodInsnNode, Set<Method>> callTargetCache = new IdentityHashMap<>();
    final Map<Method, Map<Field, Integer>> parameterWriteCache = new HashMap<>();
    final Map<SourceValue, Boolean> nullCache = new IdentityHashMap<>();
    final Map<Method, int[]> sourceLines = new HashMap<>();
    final Map<Method, Set<Signature>> functionalSignatures = new HashMap<>();
    final Map<Object, Set<Method>> dependents = new HashMap<>();
    final Deque<Method> pendingSummaries = new ArrayDeque<>();
    final Set<Method> queuedSummaries = new HashSet<>();
    Method evaluating;
    int summaryEvaluations;

    void dependOn(Object fact) {
        if (evaluating != null) dependents.computeIfAbsent(fact, ignored -> new LinkedHashSet<>()).add(evaluating);
    }
    void enqueue(Method method) {
        if (queuedSummaries.add(method)) pendingSummaries.addLast(method);
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
        for (String owner : lineage(i.owner)) {
            Field candidate = new Field(owner, i.name, i.desc);
            if (referenceFields.contains(candidate)) return candidate;
        }
        return new Field(i.owner, i.name, i.desc);
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
            if (p instanceof InvokeDynamicInsnNode d && d.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                for (Object arg : d.bsmArgs) {
                    if (arg instanceof Handle h) {
                        if (h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE) {
                            Frame<SourceValue> frame = instructionFrames.get(d);
                            int count = Type.getArgumentTypes(d.desc).length;
                            SourceValue receiver = frame != null && count > 0 ? frame.getStack(frame.getStackSize() - count) : null;
                            out.addAll(dispatch(method(h), receiver));
                        } else if (methods.containsKey(method(h))) out.add(method(h));
                    }
                }
            }
            if (p instanceof MethodInsnNode m) {
                dependOn(new ReturnedCallbacks(method(m)));
                out.addAll(returned.getOrDefault(method(m), Set.of()));
            }
            if (p instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
                dependOn(field(f));
                out.addAll(fieldCallbacks.getOrDefault(field(f), Set.of()));
            }
            if (p instanceof TypeInsnNode n && n.getOpcode() == Opcodes.NEW && classes.containsKey(n.desc)
                    && lineage(n.desc).stream().anyMatch(t -> classes.containsKey(t) && !classes.get(t).interfaces.isEmpty())) {
                Set<String> signatures = new HashSet<>();
                for (String type : lineage(n.desc)) {
                    for (var m : methods.entrySet()) {
                        if (m.getKey().owner.equals(type) && (m.getValue().access & Opcodes.ACC_PUBLIC) != 0
                                && (m.getValue().access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0
                                && !m.getKey().name.startsWith("<") && signatures.add(m.getKey().name + m.getKey().desc)) {
                            out.add(m.getKey());
                        }
                    }
                }
            }
        }
        return out;
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
            types.clear();
            for (ClassNode candidate : classes.values()) {
                if ((candidate.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0
                        && lineage(candidate.name).contains(declaration.owner)) types.add(candidate.name);
            }
        }
        Set<Method> targets = new LinkedHashSet<>();
        for (String type : types) {
            Method selected = implementation(type, declaration);
            if (selected != null) targets.add(selected);
        }
        return targets;
    }

    Set<Method> callTargets(MethodInsnNode call, Frame<SourceValue> frame) {
        Set<Method> cached = callTargetCache.get(call);
        if (cached != null) return cached;
        Method declared = method(call);
        if (call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) {
            Method selected = implementation(call.owner, declared);
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
        if (targets.isEmpty()) return null;
        Map<Field, String> needs = new LinkedHashMap<>();
        Set<Field> ensures = null;
        Set<Field> clears = new HashSet<>();
        Set<Escape> escapes = new LinkedHashSet<>();
        Set<ParameterEscape> parameterEscapes = new LinkedHashSet<>();
        Set<String> initializedClasses = new HashSet<>();
        Set<Field> invalidatedNeeds = new HashSet<>();
        for (Method target : targets) {
            Summary summary = summary(target);
            summary.needs.forEach(needs::putIfAbsent);
            if (ensures == null) ensures = new HashSet<>(summary.ensures);
            else ensures.retainAll(summary.ensures);
            clears.addAll(summary.clears);
            escapes.addAll(summary.escapes);
            parameterEscapes.addAll(summary.parameterEscapes);
            initializedClasses.addAll(summary.initializedClasses);
            invalidatedNeeds.addAll(summary.invalidatedNeeds);
        }
        return new Summary(Map.copyOf(needs), Set.copyOf(ensures), Set.copyOf(clears), Set.copyOf(escapes), Set.copyOf(parameterEscapes), Set.copyOf(initializedClasses), Set.copyOf(invalidatedNeeds));
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

    boolean definitelyNull(SourceValue value, Set<Method> visiting) {
        if (visiting.isEmpty() && value != null) {
            Boolean cached = nullCache.get(value);
            if (cached != null) return cached;
            boolean result = computeDefinitelyNull(value, visiting);
            nullCache.put(value, result);
            return result;
        }
        return computeDefinitelyNull(value, visiting);
    }

    boolean computeDefinitelyNull(SourceValue value, Set<Method> visiting) {
        if (value == null || value.insns.isEmpty()) return false;
        for (AbstractInsnNode origin : value.insns) {
            if (origin.getOpcode() == Opcodes.ACONST_NULL) continue;
            if (origin instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && referenceFields.contains(field(field)) && !writtenFields.contains(field(field))
                    && !constantReferences.contains(field(field))) continue;
            if (origin instanceof MethodInsnNode call) {
                Set<Method> callees = callTargets(call, instructionFrames.get(call));
                if (callees.isEmpty()) return false;
                for (Method callee : callees) {
                    if (!visiting.add(callee)) return false;
                    MethodNode method = methods.get(callee);
                    Frame<SourceValue>[] frames = sourceFrames.get(callee);
                    boolean returned = false;
                    if (frames == null) return false;
                    for (int i = 0; i < method.instructions.size(); i++) {
                        if (method.instructions.get(i).getOpcode() != Opcodes.ARETURN || frames[i] == null) continue;
                        returned = true;
                        SourceValue result = top(frames[i]);
                        if (result.insns.size() == 1 && parameters.containsKey(result.insns.iterator().next())) {
                            Parameter parameter = parameters.get(result.insns.iterator().next());
                            Frame<SourceValue> callFrame = instructionFrames.get(call);
                            if (callFrame == null || !definitelyNull(argument(call, callFrame, parameter.local), visiting)) return false;
                        } else if (!definitelyNull(result, visiting)) return false;
                    }
                    visiting.remove(callee);
                    if (!returned) return false;
                }
                continue;
            }
            return false;
        }
        return true;
    }

    Map<Field, Integer> parameterWrites(Method key) {
        Map<Field, Integer> cached = parameterWriteCache.get(key);
        if (cached != null) return cached;
        Map<Field, Integer> writes = computeParameterWrites(key);
        parameterWriteCache.put(key, writes);
        return writes;
    }

    Map<Field, Integer> computeParameterWrites(Method key) {
        MethodNode body = methods.get(key);
        Frame<SourceValue>[] frames = sourceFrames.get(key);
        if (body == null || frames == null || !body.tryCatchBlocks.isEmpty()) return Map.of();
        Map<Field, Integer> writes = new LinkedHashMap<>();
        for (int i = 0; i < body.instructions.size(); i++) {
            AbstractInsnNode instruction = body.instructions.get(i);
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) return Map.of();
            if (frames[i] == null || !(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC) continue;
            SourceValue value = top(frames[i]);
            Parameter parameter = value.insns.size() == 1 ? parameters.get(value.insns.iterator().next()) : null;
            if (parameter != null && parameter.method.equals(key)) writes.put(field(field), parameter.local);
            else writes.remove(field(field));
        }
        return writes;
    }
    SourceValue top(Frame<SourceValue> f) {
        return f.getStack(f.getStackSize()-1);
    }
    boolean matches(Method target, Signature signature) {
        return (target.name.equals(signature.name) && target.desc.equals(signature.descriptor))
                || functionalSignatures.getOrDefault(target, Set.of()).contains(signature);
    }
    void require(Map<Field, String> needs, Set<Field> bound, Collection<Field> fs, String where) {
        for (Field f:fs)if (!bound.contains(f))needs.putIfAbsent(f, where);
    }
    void requireReceiver(Map<Field, String> needs, Set<Field> bound, SourceValue receiver, Set<Value> nonnull,
                         Set<Field> clears, Set<Field> invalidatedNeeds, String where) {
        if (nonnull.contains(Value.of(receiver))) return;
        Set<Field> fields = origins(receiver);
        require(needs, bound, fields, where);
        for (Field field : fields) if (!bound.contains(field) && clears.contains(field)) invalidatedNeeds.add(field);
    }

    boolean currentRead(Method method, AbstractInsnNode origin, int guard, Field field) {
        MethodNode body = methods.get(method);
        int source = body.instructions.indexOf(origin);
        if (source < 0 || source >= guard) return false;
        for (int i = source + 1; i < guard; i++) {
            AbstractInsnNode instruction = body.instructions.get(i);
            if (instruction instanceof FieldInsnNode write && write.getOpcode() == Opcodes.PUTSTATIC && field(write).equals(field)) return false;
            if (instruction instanceof MethodInsnNode call) {
                Summary effects = combined(callTargets(call, instructionFrames.get(call)));
                if (effects != null && (effects.clears.contains(field) || effects.ensures.contains(field))) return false;
            }
        }
        return true;
    }
    void callback(Map<Field, String> needs, Set<Field> bound, Set<Field> clears, Set<Escape> escapes, Set<String> initializedClasses,
                  Method root, String registration, Method target, boolean emit) {
        Summary s=summary(target);
        Set<Field> atInvocation = new HashSet<>(bound);
        if (emit) atInvocation.addAll(immutableBindings);
        if (emit && !initializedClasses.contains(target.owner)) atInvocation.addAll(entryBindings(target.owner));
        for (var e:s.needs.entrySet()) {
            if (emit && !initializedClasses.contains(e.getKey().owner)) atInvocation.addAll(entryBindings(e.getKey().owner));
            if (atInvocation.contains(e.getKey()) && !s.invalidatedNeeds.contains(e.getKey())) continue;
            needs.putIfAbsent(e.getKey(), e.getValue());
            escapes.add(new Escape(registration, target, e.getKey(), e.getValue(), clears.contains(e.getKey()) || s.invalidatedNeeds.contains(e.getKey())));
            if (emit)findings.add(new Finding(root, registration, target, e.getKey(), e.getValue()));
        }
    }
    Summary flow(Method k, boolean emit) {
        MethodNode m=methods.get(k);
        Frame<SourceValue>[] ff=sourceFrames.get(k);
        if (ff==null||m.instructions.size()==0)return Summary.empty();
        AbstractInsnNode[] ins=m.instructions.toArray();
        Map<AbstractInsnNode, Integer> ix=new IdentityHashMap<>();
        for (int i=0;i<ins.length;i++)ix.put(ins[i], i);
        Map<Integer, State> states = new HashMap<>();
        Deque<Integer> work=new ArrayDeque<>();
        // A class initializer is not a global event. Only the entry class and its parents are initialized here.
        ClassNode owner = classes.get(k.owner);
        boolean initializer = k.name.equals("<clinit>");
        Set<Field> entryBindings = emit ? entryBindings(initializer && owner != null ? owner.superName : k.owner) : Set.of();
        Set<String> entryClasses = initializer && owner != null ? entryClasses(owner.superName) : entryClasses(k.owner);
        entryClasses.add(k.owner);
        states.put(0, new State(entryBindings, Set.of(), entryClasses, Set.of()));
        work.add(0);
        Map<Field, String> needs=new LinkedHashMap<>();
        Set<Field> exits = null, touchedClears = new HashSet<>(), invalidatedNeeds = new HashSet<>();
        Set<Escape> escapes=new LinkedHashSet<>();
        Set<ParameterEscape> parameterEscapes = new LinkedHashSet<>();
        Set<String> touchedClasses = new HashSet<>(entryClasses);
        int steps=0;
        while (!work.isEmpty()) {
            if (++steps>100000) {
                analysisErrors.add(k+": dataflow budget exceeded");
                return Summary.empty();
            }
            int i=work.remove();
            if (ff[i]==null)continue;
            State before = states.get(i);
            Set<Field> bound = new HashSet<>(before.bound), clears = new HashSet<>(before.cleared);
            Set<Value> nonnull = new HashSet<>(before.nonnull);
            Set<String> initialized = new HashSet<>(before.initialized);
            AbstractInsnNode n=ins[i];
            nonnull.removeIf(value -> value.origins.contains(n));
            Frame<SourceValue> f=ff[i];
            int op=n.getOpcode();
            String activeClass = n instanceof TypeInsnNode type && op == Opcodes.NEW ? type.desc
                    : n instanceof FieldInsnNode field && (op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC) ? field(field).owner
                    : n instanceof MethodInsnNode call && op == Opcodes.INVOKESTATIC ? call.owner : null;
            if (activeClass != null && !initialized.contains(activeClass)) {
                if (emit) bound.addAll(entryBindings(activeClass));
                initialized.addAll(entryClasses(activeClass));
            }
            if (n instanceof FieldInsnNode fi) {
                if (op==Opcodes.PUTSTATIC && referenceFields.contains(field(fi))) {
                    SourceValue v=top(f);
                    boolean nil = definitelyNull(v, new HashSet<>());
                    if (nil){
                        bound.remove(field(fi));
                        clears.add(field(fi));
                    } else bound.add(field(fi));
                }
                if (op==Opcodes.GETFIELD)requireReceiver(needs, bound, top(f), nonnull, clears, invalidatedNeeds, site(k, i));
                if (op==Opcodes.PUTFIELD)requireReceiver(needs, bound, f.getStack(f.getStackSize()-2), nonnull, clears, invalidatedNeeds, site(k, i));
            }
            if (n instanceof MethodInsnNode call) {
                Type[] args=Type.getArgumentTypes(call.desc);
                int first=f.getStackSize()-args.length;
                if (op!=Opcodes.INVOKESTATIC)requireReceiver(needs, bound, f.getStack(first-1), nonnull, clears, invalidatedNeeds, site(k, i));
                if (op == Opcodes.INVOKEINTERFACE || op == Opcodes.INVOKEVIRTUAL) {
                    // A call through a formal receiver executes the selected method synchronously.
                    // Keep its signature until a caller supplies the concrete callback value.
                    for (AbstractInsnNode origin : f.getStack(first - 1).insns) {
                        Parameter parameter = parameters.get(origin);
                        if (parameter != null && parameter.method.equals(k)) {
                            parameterEscapes.add(new ParameterEscape(parameter.local, site(k, i),
                                    Set.copyOf(bound), Set.copyOf(clears), new Signature(call.name, call.desc)));
                        }
                    }
                }
                Summary s = combined(callTargets(call, f));
                if (s!=null) {
                    for (var e : s.needs.entrySet()) {
                        if (s.invalidatedNeeds.contains(e.getKey())) {
                            needs.putIfAbsent(e.getKey(), e.getValue());
                            invalidatedNeeds.add(e.getKey());
                        } else if (!bound.contains(e.getKey())) {
                            needs.putIfAbsent(e.getKey(), e.getValue());
                            if (clears.contains(e.getKey())) invalidatedNeeds.add(e.getKey());
                        }
                    }
                    for (Escape e:s.escapes)if (e.cleared || (!bound.contains(e.dependency)
                            && !(emit && immutableBindings.contains(e.dependency)))) {
                        escapes.add(e);
                        if (emit)findings.add(new Finding(k, e.registration, e.callback, e.dependency, e.read));
                    }
                    for (ParameterEscape effect : s.parameterEscapes) {
                        SourceValue actual = argument(call, f, effect.local);
                        if (actual == null) continue;
                        Set<Field> atExposure = new HashSet<>(bound);
                        atExposure.removeAll(effect.clears);
                        atExposure.addAll(effect.bound);
                        for (Method target : targets(actual)) {
                            if (effect.invocation != null && !matches(target, effect.invocation)) continue;
                            callback(needs, atExposure, clears, escapes, initialized, k, effect.registration, target, emit);
                        }
                        for (AbstractInsnNode origin : actual.insns) {
                            Parameter parameter = parameters.get(origin);
                            if (parameter != null && parameter.method.equals(k)) {
                                parameterEscapes.add(new ParameterEscape(parameter.local, effect.registration,
                                        Set.copyOf(atExposure), Set.copyOf(clears), effect.invocation));
                            }
                        }
                    }
                    bound.removeAll(s.clears);
                    clears.addAll(s.clears);
                    bound.addAll(s.ensures);
                    initialized.addAll(s.initializedClasses);
                    for (Method target : callTargets(call, f)) {
                        for (var write : parameterWrites(target).entrySet()) {
                            if (definitelyNull(argument(call, f, write.getValue()), new HashSet<>())) {
                                bound.remove(write.getKey());
                                clears.add(write.getKey());
                            }
                        }
                    }
                } else {
                    for (int a=first;a<f.getStackSize();a++) {
                        SourceValue actual = f.getStack(a);
                        for (Method target : targets(actual)) callback(needs, bound, clears, escapes, initialized, k, site(k, i), target, emit);
                        for (AbstractInsnNode origin : actual.insns) {
                            Parameter parameter = parameters.get(origin);
                            if (parameter != null && parameter.method.equals(k)) {
                                parameterEscapes.add(new ParameterEscape(parameter.local, site(k, i), Set.copyOf(bound), Set.copyOf(clears), null));
                            }
                        }
                    }
                }
            }
            if (op == Opcodes.ARRAYLENGTH || op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) {
                requireReceiver(needs, bound, top(f), nonnull, clears, invalidatedNeeds, site(k, i));
            }
            if (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) {
                requireReceiver(needs, bound, f.getStack(f.getStackSize() - 2), nonnull, clears, invalidatedNeeds, site(k, i));
            }
            if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
                requireReceiver(needs, bound, f.getStack(f.getStackSize() - 3), nonnull, clears, invalidatedNeeds, site(k, i));
            }
            if (op>=Opcodes.IRETURN&&op<=Opcodes.RETURN) {
                if (exits==null)exits=new HashSet<>(bound);
                else exits.retainAll(bound);
            }
            touchedClasses.addAll(initialized);
            touchedClears.addAll(clears);
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
            for (int dest:next)merge(states, work, dest, new State(branch.getOrDefault(dest, bound), branchValues.getOrDefault(dest, nonnull), initialized, clears));
            for (TryCatchBlockNode t:m.tryCatchBlocks)if (i>=ix.get(t.start)&&i<ix.get(t.end)) merge(states, work, ix.get(t.handler), before);
        }
        return new Summary(Map.copyOf(needs), exits==null?Set.of():Set.copyOf(exits), Set.copyOf(touchedClears), Set.copyOf(escapes), Set.copyOf(parameterEscapes), Set.copyOf(touchedClasses), Set.copyOf(invalidatedNeeds));
    }
    static void merge(Map<Integer, State> states, Deque<Integer> work, int dest, State incoming) {
        State old = states.get(dest);
        if (old == null) {
            states.put(dest, incoming);
            work.add(dest);
            return;
        }
        Set<Field> bound = new HashSet<>(old.bound);
        bound.retainAll(incoming.bound);
        Set<Value> values = new HashSet<>(old.nonnull);
        values.retainAll(incoming.nonnull);
        Set<String> initialized = new HashSet<>(old.initialized);
        initialized.addAll(incoming.initialized);
        Set<Field> cleared = new HashSet<>(old.cleared);
        cleared.addAll(incoming.cleared);
        State joined = new State(bound, values, initialized, cleared);
        if (!joined.equals(old)) {
            states.put(dest, joined);
            work.add(dest);
        }
    }
    void analyze() {
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
        methods.keySet().forEach(this::enqueue);
        long budget = Math.max(10000L, 100L * methods.size());
        while (!pendingSummaries.isEmpty()) {
            if (++summaryEvaluations > budget) {
                analysisErrors.add("interprocedural summary worklist budget exceeded; fixed point not established");
                break;
            }
            Method key = pendingSummaries.removeFirst();
            queuedSummaries.remove(key);
            evaluating = key;
            callTargetCache.clear();
            nullCache.clear();
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
            if (!value.equals(summaries.get(key))) {
                summaries.put(key, value);
                changed(key);
            }
            if (key.name.equals("<clinit>")) {
                Set<Field> previous = classInitializationBindings.put(key.owner, value.ensures);
                if (!value.ensures.equals(previous)) changed(key.owner);
            }
        }
        evaluating = null;
        callTargetCache.clear();
        nullCache.clear();
        Set<Method> inbound=new HashSet<>();
        for (MethodNode m:methods.values())for (AbstractInsnNode i:m.instructions) {
            if (i instanceof MethodInsnNode call) inbound.addAll(callTargets(call, instructionFrames.get(i)));
            if (i instanceof InvokeDynamicInsnNode d)for (Object arg:d.bsmArgs)if (arg instanceof Handle h)inbound.add(method(h));
        }
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
        for (var e:methods.entrySet())if ((!inbound.contains(e.getKey())&&(e.getValue().access&Opcodes.ACC_PUBLIC)!=0)
                || (e.getKey().name.equals("<clinit>") && !summaries.get(e.getKey()).escapes.isEmpty())) {
            entryRoots.add(e.getKey());
            flow(e.getKey(), true);
        }
    }
    record Result(int classes, int methods, int analyzedBodies, List<Method> roots, List<Finding> findings, List<String> analysisErrors,
                  int summaryEvaluations, List<Dependency> dependencies) {
        int dependencyEdges() { return dependencies.size(); }
    }

    List<Dependency> dependencyGraph() {
        List<Dependency> edges = new ArrayList<>();
        dependents.forEach((fact, users) -> {
            String type = fact instanceof Method ? "method-summary" : fact instanceof ReturnedCallbacks ? "returned-callbacks"
                    : fact instanceof Field ? "field-callbacks" : "class-initialization";
            String name = fact instanceof ReturnedCallbacks value ? value.method.toString()
                    : fact instanceof Field field ? field + ":" + field.desc : fact.toString();
            users.forEach(user -> edges.add(new Dependency(type, name, user)));
        });
        return edges.stream().sorted(Comparator.comparing(Dependency::factType).thenComparing(Dependency::fact)
                .thenComparing(edge -> edge.dependent.toString())).toList();
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

    Set<String> entryClasses(String owner) {
        Set<String> initialized = new HashSet<>();
        if (owner == null) return initialized;
        Deque<String> pending = new ArrayDeque<>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            String type = pending.removeFirst();
            if (!initialized.add(type)) continue;
            ClassNode node = classes.get(type);
            if (node != null && node.superName != null) pending.add(node.superName);
            Method initializerKey = new Method(type, "<clinit>", "()V");
            dependOn(initializerKey);
            Summary initializer = summaries.get(initializerKey);
            if (initializer != null) pending.addAll(initializer.initializedClasses);
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
        CallbackInitialization analysis = new CallbackInitialization();
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
                analysis.dependencyGraph());
    }
}
