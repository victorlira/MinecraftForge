/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.fml.common.asm;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.slf4j.Logger;

/**
 * Modifies specified enums to allow runtime extension by making the $VALUES field non-final and
 * injecting constructor calls which are not valid in normal java code.
 */
public class RuntimeEnumExtender implements ILaunchPluginService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Type STRING = Type.getType(String.class);
    private static final Type ENUM = Type.getType(Enum.class);
    private static final Type MARKER_IFACE = Type.getType("Lnet/minecraftforge/common/IExtensibleEnum;");
    private static final Type ARRAY_UTILS = Type.getType("Lorg/apache/commons/lang3/ArrayUtils;"); //Don't directly reference this to prevent class loading.
    private static final String ADD_DESC = Type.getMethodDescriptor(Type.getType(Object[].class), Type.getType(Object[].class), Type.getType(Object.class));
    private static final Type UNSAFE_HACKS = Type.getType("Lnet/minecraftforge/fml/unsafe/UnsafeHacks;"); //Again, not direct reference to prevent class loading.
    private static final String CLEAN_DESC = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Class.class));
    private static final String NAME_DESC = Type.getMethodDescriptor(STRING);
    private static final String EQUALS_DESC = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, STRING);
    private static final int FLAGS = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;

    @Override
    public String name() {
        return "runtime_enum_extender";
    }

    private static final EnumSet<Phase> YAY = EnumSet.of(Phase.AFTER);
    private static final EnumSet<Phase> NAY = EnumSet.noneOf(Phase.class);

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty)
    {
        return isEmpty ? NAY : YAY;
    }

    @Override
    public int processClassWithFlags(final Phase phase, final ClassNode classNode, final Type classType, final String reason)
    {
        if ((classNode.access & Opcodes.ACC_ENUM) == 0)
            return ComputeFlags.NO_REWRITE;

        Type array = Type.getType("[" + classType.getDescriptor());
        String arrayDesc = array.getDescriptor();

        FieldNode values = classNode.fields.stream().filter(f -> f.desc.equals(arrayDesc) && ((f.access & FLAGS) == FLAGS)).findFirst().orElse(null);

        if (!classNode.interfaces.contains(MARKER_IFACE.getInternalName())) {
            return ComputeFlags.NO_REWRITE;
        }

        //Static methods named "create" with first argument as a string
        List<MethodNode> candidates = classNode.methods.stream()
                .filter(m -> ((m.access & Opcodes.ACC_STATIC) != 0) && m.name.equals("create"))
                .toList();

        if (candidates.isEmpty()) {
            throw new IllegalStateException("IExtensibleEnum has no candidate factory methods: " + classType.getClassName());
        }

        for (var mtd : candidates)
        {
            Type[] args = Type.getArgumentTypes(mtd.desc);
            if (args.length == 0 || !args[0].equals(STRING)) {
                if (LOGGER.isErrorEnabled(LogUtils.FATAL_MARKER))
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Enum has create method without String as first parameter:\n");
                    sb.append("  Enum: ").append(classType.getDescriptor()).append("\n");
                    sb.append("  Target: ").append(mtd.name).append(mtd.desc).append("\n");
                    LOGGER.error(LogUtils.FATAL_MARKER, sb.toString());
                }
                throw new IllegalStateException("Enum has create method without String as first parameter: " + mtd.name + mtd.desc);
            }

            Type ret = Type.getReturnType(mtd.desc);
            if (!ret.equals(classType)) {
                if (LOGGER.isErrorEnabled(LogUtils.FATAL_MARKER))
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Enum has create method with incorrect return type:\n");
                    sb.append("  Enum: ").append(classType.getDescriptor()).append("\n");
                    sb.append("  Target: ").append(mtd.name).append(mtd.desc).append("\n");
                    sb.append("  Found: ").append(ret.getClassName()).append(", Expected: ").append(classType.getClassName());
                    LOGGER.error(LogUtils.FATAL_MARKER, sb.toString());
                }
                throw new IllegalStateException("Enum has create method with incorrect return type: " + mtd.name + mtd.desc);
            }

            Type[] ctrArgs = new Type[args.length + 1];
            ctrArgs[0] = STRING;
            ctrArgs[1] = Type.INT_TYPE;
            System.arraycopy(args, 1, ctrArgs, 2, args.length - 1);

            String desc = Type.getMethodDescriptor(Type.VOID_TYPE, ctrArgs);

            MethodNode ctr = classNode.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(desc)).findFirst().orElse(null);
            if (ctr == null)
            {
                if (LOGGER.isErrorEnabled(LogUtils.FATAL_MARKER))
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Enum has create method with no matching constructor:\n");
                    sb.append("  Enum: ").append(classType.getDescriptor()).append("\n");
                    sb.append("  Candidate: ").append(mtd.desc).append("\n");
                    sb.append("  Target: ").append(desc).append("\n");
                    classNode.methods.stream().filter(m -> m.name.equals("<init>")).forEach(m -> sb.append("        : ").append(m.desc).append("\n"));
                    LOGGER.error(LogUtils.FATAL_MARKER, sb.toString());
                }
                throw new IllegalStateException("Enum has create method with no matching constructor: " + desc);
            }

            if (values == null)
            {
                if (LOGGER.isErrorEnabled(LogUtils.FATAL_MARKER))
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Enum has create method but we could not find $VALUES. Found:\n");
                    classNode.fields.stream().filter(f -> (f.access & Opcodes.ACC_STATIC) != 0).
                            forEach(m -> sb.append("  ").append(m.name).append(" ").append(m.desc).append("\n"));
                    LOGGER.error(LogUtils.FATAL_MARKER, sb.toString());
                }
                throw new IllegalStateException("Enum has create method but we could not find $VALUES");
            }

            values.access &= values.access & ~Opcodes.ACC_FINAL; //Strip the final so JITer doesn't inline things.

            mtd.access |= Opcodes.ACC_SYNCHRONIZED;
            mtd.instructions.clear();
            mtd.localVariables.clear();
            if (mtd.tryCatchBlocks != null)
            {
                mtd.tryCatchBlocks.clear();
            }
            if (mtd.visibleLocalVariableAnnotations != null)
            {
                mtd.visibleLocalVariableAnnotations.clear();
            }
            if (mtd.invisibleLocalVariableAnnotations != null)
            {
                mtd.invisibleLocalVariableAnnotations.clear();
            }
            InstructionAdapter ins = new InstructionAdapter(mtd);

            int vars = 0;
            for (Type arg : args)
                vars += arg.getSize();

            {
                vars += 1; //int x
                Label for_start = new Label();
                Label for_condition = new Label();
                Label for_inc = new Label();

                ins.iconst(0);
                ins.store(vars, Type.INT_TYPE);
                ins.goTo(for_condition);
                //if (!VALUES[x].name().equalsIgnoreCase(name)) goto for_inc
                ins.mark(for_start);
                ins.getstatic(classType.getInternalName(), values.name, values.desc);
                ins.load(vars, Type.INT_TYPE);
                ins.aload(array);
                ins.invokevirtual(ENUM.getInternalName(), "name", NAME_DESC, false);
                ins.load(0, STRING);
                ins.invokevirtual(STRING.getInternalName(), "equalsIgnoreCase", EQUALS_DESC, false);
                ins.ifeq(for_inc);
                //return VALUES[x];
                ins.getstatic(classType.getInternalName(), values.name, values.desc);
                ins.load(vars, Type.INT_TYPE);
                ins.aload(array);
                ins.areturn(classType);
                //x++
                ins.mark(for_inc);
                ins.iinc(vars, 1);
                //if (x < VALUES.length) goto for_start
                ins.mark(for_condition);
                ins.load(vars, Type.INT_TYPE);
                ins.getstatic(classType.getInternalName(), values.name, values.desc);
                ins.arraylength();
                ins.ificmplt(for_start);
            }

            {
                vars += 1; //enum ret;
                //ret = new ThisType(name, VALUES.length, args..)
                ins.anew(classType);
                ins.dup();
                ins.load(0, STRING);
                ins.getstatic(classType.getInternalName(), values.name, values.desc);
                ins.arraylength();
                int idx = 1;
                for (int x = 1; x < args.length; x++)
                {
                    ins.load(idx, args[x]);
                    idx += args[x].getSize();
                }
                ins.invokespecial(classType.getInternalName(), "<init>", desc, false);
                ins.store(vars, classType);
                // VALUES = ArrayUtils.add(VALUES, ret)
                ins.getstatic(classType.getInternalName(), values.name, values.desc);
                ins.load(vars, classType);
                ins.invokestatic(ARRAY_UTILS.getInternalName(), "add", ADD_DESC, false);
                ins.checkcast(array);
                ins.putstatic(classType.getInternalName(), values.name, values.desc);
                //EnumHelper.cleanEnumCache(ThisType.class)
                ins.visitLdcInsn(classType);
                ins.invokestatic(UNSAFE_HACKS.getInternalName(), "cleanEnumCache", CLEAN_DESC, false);
                //init ret
                ins.load(vars, classType);
                ins.invokeinterface(MARKER_IFACE.getInternalName(), "init", "()V");
                //return ret
                ins.load(vars, classType);
                ins.areturn(classType);
            }
        }
        return ComputeFlags.COMPUTE_FRAMES;
    }

}
