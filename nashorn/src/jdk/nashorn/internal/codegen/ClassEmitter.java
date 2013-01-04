/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.nashorn.internal.codegen;

import static jdk.internal.org.objectweb.asm.Opcodes.ACC_FINAL;
import static jdk.internal.org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static jdk.internal.org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static jdk.internal.org.objectweb.asm.Opcodes.ACC_STATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.ACC_SUPER;
import static jdk.internal.org.objectweb.asm.Opcodes.ACC_VARARGS;
import static jdk.internal.org.objectweb.asm.Opcodes.H_INVOKEINTERFACE;
import static jdk.internal.org.objectweb.asm.Opcodes.H_INVOKESPECIAL;
import static jdk.internal.org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL;
import static jdk.internal.org.objectweb.asm.Opcodes.H_NEWINVOKESPECIAL;
import static jdk.internal.org.objectweb.asm.Opcodes.V1_7;
import static jdk.nashorn.internal.codegen.CompilerConstants.CLINIT;
import static jdk.nashorn.internal.codegen.CompilerConstants.CONSTANTS;
import static jdk.nashorn.internal.codegen.CompilerConstants.GET_ARRAY_PREFIX;
import static jdk.nashorn.internal.codegen.CompilerConstants.GET_ARRAY_SUFFIX;
import static jdk.nashorn.internal.codegen.CompilerConstants.GET_MAP;
import static jdk.nashorn.internal.codegen.CompilerConstants.GET_STRING;
import static jdk.nashorn.internal.codegen.CompilerConstants.INIT;
import static jdk.nashorn.internal.codegen.CompilerConstants.SET_MAP;
import static jdk.nashorn.internal.codegen.CompilerConstants.SOURCE;
import static jdk.nashorn.internal.codegen.CompilerConstants.STRICT_MODE;
import static jdk.nashorn.internal.codegen.CompilerConstants.className;
import static jdk.nashorn.internal.codegen.CompilerConstants.methodDescriptor;
import static jdk.nashorn.internal.codegen.CompilerConstants.staticCallNoLookup;
import static jdk.nashorn.internal.codegen.CompilerConstants.typeDescriptor;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.util.TraceClassVisitor;
import jdk.nashorn.internal.codegen.types.Type;
import jdk.nashorn.internal.ir.FunctionNode;
import jdk.nashorn.internal.runtime.Context;
import jdk.nashorn.internal.runtime.PropertyMap;
import jdk.nashorn.internal.runtime.ScriptObject;
import jdk.nashorn.internal.runtime.Source;

/**
 * The interface responsible for speaking to ASM, emitting classes,
 * fields and methods.
 * <p>
 * This file contains the ClassEmitter, which is the master object
 * responsible for writing byte codes. It utilizes a MethodEmitter
 * for method generation, which also the NodeVisitors own, to keep
 * track of the current code generator and what it is doing.
 * <p>
 * There is, however, nothing stopping you from using this in a
 * completely self contained environment, for example in ObjectGenerator
 * where there are no visitors or external hooks.
 * <p>
 * MethodEmitter makes it simple to generate code for methods without
 * having to do arduous type checking. It maintains a type stack
 * and will pick the appropriate operation for all operations sent to it
 * We also allow chained called to a MethodEmitter for brevity, e.g.
 * it is legal to write _new(className).dup() or
 * load(slot).load(slot2).xor().store(slot3);
 * <p>
 * If running with assertions enabled, any type conflict, such as different
 * bytecode stack sizes or operating on the wrong type will be detected
 * and an error thrown.
 * <p>
 * There is also a very nice debug interface that can emit formatted
 * bytecodes that have been written. This is enabled by setting the
 * environment "nashorn.codegen.debug" to true, or --log=codegen:<level>
 * <p>
 * A ClassEmitter implements an Emitter - i.e. it needs to have
 * well defined start and end calls for whatever it is generating. Assertions
 * detect if this is not true
 *
 * @see Compiler
 * @see CodeGenerator
 */
public class ClassEmitter implements Emitter {

    /** Sanity check flag - have we started on a class? */
    private boolean classStarted;

    /** Sanity check flag - have we ended this emission? */
    private boolean classEnded;

    /**
     * Sanity checks - which methods have we currently
     * started for generation in this class?
     */
    private final HashSet<MethodEmitter> methodsStarted;

    /** The ASM classwriter that we use for all bytecode operations */
    protected final ClassWriter cw;

    /** The context */
    protected final Context context;

    /** Default flags for class generation - oublic class */
    private static final EnumSet<Flag> DEFAULT_METHOD_FLAGS = EnumSet.of(Flag.PUBLIC);

    /** Compile unit class name. */
    private String unitClassName;

    /** Set of constants access methods required. */
    private Set<Class<?>> constantMethodNeeded;

    /**
     * Constructor - only used internally in this class as it breaks
     * abstraction towards ASM or other code generator below
     *
     * @param context  context
     * @param cw       ASM classwriter
     */
    private ClassEmitter(final Context context, final ClassWriter cw) {
        assert context != null;

        this.context        = context;
        this.cw             = cw;
        this.methodsStarted = new HashSet<>();
    }

    /**
     * Constructor
     *
     * @param context         context
     * @param className       name of class to weave
     * @param superClassName  super class name for class
     * @param interfaceNames  names of interfaces implemented by this class, or null if none
     */
    public ClassEmitter(final Context context, final String className, final String superClassName, final String... interfaceNames) {
        this(context, new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));
        cw.visit(V1_7, ACC_PUBLIC | ACC_SUPER, className, null, superClassName, interfaceNames);
    }

    /**
     * Constructor from the compiler
     *
     * @param compiler      Compiler
     * @param unitClassName Compile unit class name.
     * @param strictMode    Should we generate this method in strict mode
     */
    ClassEmitter(final Compiler compiler, final String unitClassName, final boolean strictMode) {
        this(compiler.getContext(),
             new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                private static final String OBJECT_CLASS  = "java/lang/Object";

                @Override
                protected String getCommonSuperClass(final String type1, final String type2) {
                    try {
                        return super.getCommonSuperClass(type1, type2);
                    } catch (final RuntimeException e) {
                        if (isScriptObject(Compiler.SCRIPTS_PACKAGE, type1) && isScriptObject(Compiler.SCRIPTS_PACKAGE, type2)) {
                            return className(ScriptObject.class);
                        }
                        return OBJECT_CLASS;
                    }
                }
            });

        this.unitClassName        = unitClassName;
        this.constantMethodNeeded = new HashSet<>();

        cw.visit(V1_7, ACC_PUBLIC | ACC_SUPER, unitClassName, null, Compiler.pathName(jdk.nashorn.internal.scripts.JS$.class.getName()), null);
        cw.visitSource(compiler.getSource().getName(), null);

        defineCommonStatics(strictMode);
    }

    /**
     * Define the static fields common in all scripts.
     * @param strictMode Should we generate this method in strict mode
     */
    private void defineCommonStatics(final boolean strictMode) {
        // source - used to store the source data (text) for this script.  Shared across
        // compile units.  Set externally by the compiler.
        field(EnumSet.of(Flag.PUBLIC, Flag.STATIC), SOURCE.tag(), Source.class);

        // constants - used to the constants array for this script.  Shared across
        // compile units.  Set externally by the compiler.
        field(EnumSet.of(Flag.PUBLIC, Flag.STATIC), CONSTANTS.tag(), Object[].class);

        // strictMode - was this script compiled in strict mode.  Set externally by the compiler.
        field(EnumSet.of(Flag.PUBLIC, Flag.STATIC, Flag.FINAL), STRICT_MODE.tag(), boolean.class, strictMode);
    }

    /**
     * Define static utilities common needed in scripts.  These are per compile unit
     * and therefore have to be defined here and not in code gen.
     */
    private void defineCommonUtilities() {
        assert unitClassName != null;

        if (constantMethodNeeded.contains(String.class)) {
            // $getString - get the ith entry from the constants table and cast to String.
            final MethodEmitter getStringMethod = method(EnumSet.of(Flag.PRIVATE, Flag.STATIC), GET_STRING.tag(), String.class, int.class);
            getStringMethod.begin();
            getStringMethod.getStatic(unitClassName, CONSTANTS.tag(), CONSTANTS.descriptor())
                        .load(Type.INT, 0)
                        .arrayload()
                        .checkcast(String.class)
                        ._return();
            getStringMethod.end();
        }

        if (constantMethodNeeded.contains(PropertyMap.class)) {
            // $getMap - get the ith entry from the constants table and cast to PropertyMap.
            final MethodEmitter getMapMethod = method(EnumSet.of(Flag.PUBLIC, Flag.STATIC), GET_MAP.tag(), PropertyMap.class, int.class);
            getMapMethod.begin();
            getMapMethod.loadConstants(unitClassName)
                        .load(Type.INT, 0)
                        .arrayload()
                        .checkcast(PropertyMap.class)
                        ._return();
            getMapMethod.end();

            // $setMap - overwrite an existing map.
            final MethodEmitter setMapMethod = method(EnumSet.of(Flag.PUBLIC, Flag.STATIC), SET_MAP.tag(), void.class, int.class, PropertyMap.class);
            setMapMethod.begin();
            setMapMethod.loadConstants(unitClassName)
                        .load(Type.INT, 0)
                        .load(Type.OBJECT, 1)
                        .arraystore();
            setMapMethod.returnVoid();
            setMapMethod.end();
        }

        // $getXXXX$array - get the ith entry from the constants table and cast to XXXX[].
        for (final Class<?> cls : constantMethodNeeded) {
            if (cls.isArray()) {
                defineGetArrayMethod(cls);
            }
        }
    }

    /**
     * Constructs a primitive specific method for getting the ith entry from the constants table and cast.
     * @param cls Array class.
     */
    private void defineGetArrayMethod(final Class<?> cls) {
        assert unitClassName != null;

        final String        methodName     = getArrayMethodName(cls);
        final MethodEmitter getArrayMethod = method(EnumSet.of(Flag.PRIVATE, Flag.STATIC), methodName, cls, int.class);

        getArrayMethod.begin();
        getArrayMethod.getStatic(unitClassName, CONSTANTS.tag(), CONSTANTS.descriptor())
                      .load(Type.INT, 0)
                      .arrayload()
                      .checkcast(cls)
                      .dup()
                      .arraylength()
                      .invoke(staticCallNoLookup(Arrays.class, "copyOf", cls, cls, int.class))
                      ._return();
        getArrayMethod.end();
    }

    /**
     * Generate the name of a get array from constant pool method.
     * @param cls Name of array class.
     * @return Method name.
     */
    static String getArrayMethodName(final Class<?> cls) {
        assert cls.isArray();
        return GET_ARRAY_PREFIX.tag() + cls.getComponentType().getSimpleName() + GET_ARRAY_SUFFIX.tag();
    }

    /**
     * Ensure a get constant method is issued for the class.
     * @param cls Class of constant.
     */
    public void needGetConstantMethod(final Class<?> cls) {
        constantMethodNeeded.add(cls);
    }

    /**
     * Inspect class name and decide whether we are generating a ScriptObject class
     *
     * @param scriptPrefix the script class prefix for the current script
     * @param type         the type to check
     *
     * @return true if type is ScriptObject
     */
    private static boolean isScriptObject(final String scriptPrefix, final String type) {
        if (type.startsWith(scriptPrefix)) {
            return true;
        } else if (type.equals(CompilerConstants.className(ScriptObject.class))) {
            return true;
        } else if (type.startsWith(Compiler.OBJECTS_PACKAGE)) {
            return true;
        }

        return false;
    }

    /**
     * Call at beginning of class emission
     * @see Emitter
     */
    @Override
    public void begin() {
        classStarted = true;
    }

    /**
     * Call at end of class emission
     * @see Emitter
     */
    @Override
    public void end() {
        assert classStarted;

        if (unitClassName != null) {
            defineCommonUtilities();
        }

        cw.visitEnd();
        classStarted = false;
        classEnded   = true;
        assert methodsStarted.isEmpty() : "methodsStarted not empty " + methodsStarted;
    }

    /**
     * Disassemble an array of byte code.
     *
     * @param context   the context
     * @param bytecode  byte array representing bytecode
     */
    public static void disassemble(final Context context, final byte[] bytecode) {
        new ClassReader(bytecode).accept(new TraceClassVisitor(context.getErr()), 0);
    }

    /**
     * Verify an array of byte code as a valid Java class
     *
     * @param context  the context
     * @param bytecode the bytecode array
     */
    public static void verify(final Context context, final byte[] bytecode) {
        context.verify(bytecode);
    }

    /**
     * @return context used for class emission
     */
    Context getContext() {
        return context;
    }

    /**
     * Call back from MethodEmitter for method start
     *
     * @see MethodEmitter
     *
     * @param method method emitter.
     */
    void beginMethod(final MethodEmitter method) {
        assert !methodsStarted.contains(method);
        methodsStarted.add(method);
    }

    /**
     * Call back from MethodEmitter for method end
     *
     * @see MethodEmitter
     *
     * @param method
     */
    void endMethod(final MethodEmitter method) {
        assert methodsStarted.contains(method);
        methodsStarted.remove(method);
    }

    /**
     * Add a new method to the class - defaults to public method
     *
     * @param methodName name of method
     * @param rtype      return type of the method
     * @param ptypes     parameter types the method
     *
     * @return method emitter to use for weaving this method
     */
    public MethodEmitter method(final String methodName, final Class<?> rtype, final Class<?>... ptypes) {
        return method(DEFAULT_METHOD_FLAGS, methodName, rtype, ptypes); //TODO why public default ?
    }

    /**
     * Add a new method to the class - defaults to public method
     *
     * @param methodFlags access flags for the method
     * @param methodName  name of method
     * @param rtype       return type of the method
     * @param ptypes      parameter types the method
     *
     * @return method emitter to use for weaving this method
     */
    public MethodEmitter method(final EnumSet<Flag> methodFlags, final String methodName, final Class<?> rtype, final Class<?>... ptypes) {
        return new MethodEmitter(this, cw.visitMethod(Flag.getValue(methodFlags), methodName, methodDescriptor(rtype, ptypes), null, null));
    }

    /**
     * Add a new method to the class - defaults to public method
     *
     * @param methodName name of method
     * @param descriptor descriptor of method
     *
     * @return method emitter to use for weaving this method
     */
    public MethodEmitter method(final String methodName, final String descriptor) {
        return method(DEFAULT_METHOD_FLAGS, methodName, descriptor);
    }

    /**
     * Add a new method to the class - defaults to public method
     *
     * @param methodFlags access flags for the method
     * @param methodName  name of method
     * @param descriptor  descriptor of method
     *
     * @return method emitter to use for weaving this method
     */
    public MethodEmitter method(final EnumSet<Flag> methodFlags, final String methodName, final String descriptor) {
        return new MethodEmitter(this, cw.visitMethod(Flag.getValue(methodFlags), methodName, descriptor, null, null));
    }

    /**
     * Add a new method to the class, representing a function node
     *
     * @param functionNode the function node to generate a method for
     * @return method emitter to use for weaving this method
     */
    public MethodEmitter method(final FunctionNode functionNode) {
        final MethodVisitor mv = cw.visitMethod(
            ACC_PUBLIC | ACC_STATIC | (functionNode.isVarArg() ? ACC_VARARGS : 0),
            functionNode.getName(),
            FunctionSignature.functionSignature(functionNode),
            null,
            null);

        return new MethodEmitter(this, mv, functionNode);
    }

    /**
     * Start generating the <clinit> method in the class
     *
     * @return method emitter to use for weaving <clinit>
     */
    public MethodEmitter clinit() {
        return method(EnumSet.of(Flag.STATIC), CLINIT.tag(), void.class);
    }

    /**
     * Start generating an <init>()V method in the class
     *
     * @return method emitter to use for weaving <init>()V
     */
    public MethodEmitter init() {
        return method(INIT.tag(), void.class);
    }

    /**
     * Start generating an <init>()V method in the class
     *
     * @param ptypes parameter types for constructor
     * @return method emitter to use for weaving <init>()V
     */
    public MethodEmitter init(final Class<?>... ptypes) {
        return method(INIT.tag(), void.class, ptypes);
    }

    /**
     * Start generating an <init>(...)V method in the class
     *
     * @param flags  access flags for the constructor
     * @param ptypes parameter types for the constructor
     *
     * @return method emitter to use for weaving <init>(...)V
     */
    public MethodEmitter init(final EnumSet<Flag> flags, final Class<?>... ptypes) {
        return method(flags, INIT.tag(), void.class, ptypes);
    }

    /**
     * Add a field to the class, initialized to a value
     *
     * @param fieldFlags flags, e.g. should it be static or public etc
     * @param fieldName  name of field
     * @param fieldType  the type of the field
     * @param value      the value
     *
     * @see ClassEmitter.Flag
     */
    public final void field(final EnumSet<Flag> fieldFlags, final String fieldName, final Class<?> fieldType, final Object value) {
        cw.visitField(Flag.getValue(fieldFlags), fieldName, typeDescriptor(fieldType), null, value).visitEnd();
    }

    /**
     * Add a field to the class
     *
     * @param fieldFlags access flags for the field
     * @param fieldName  name of field
     * @param fieldType  type of the field
     *
     * @see ClassEmitter.Flag
     */
    public final void field(final EnumSet<Flag> fieldFlags, final String fieldName, final Class<?> fieldType) {
        field(fieldFlags, fieldName, fieldType, null);
    }

    /**
     * Add a field to the class - defaults to public
     *
     * @param fieldName  name of field
     * @param fieldType  type of field
     */
    public final void field(final String fieldName, final Class<?> fieldType) {
        field(EnumSet.of(Flag.PUBLIC), fieldName, fieldType, null);
    }

    /**
     * Return a bytecode array from this ClassEmitter. The ClassEmitter must
     * have been ended (having its end function called) for this to work.
     *
     * @return byte code array for generated class, null if class generation hasn't been ended with {@link ClassEmitter#end()}
     */
    public byte[] toByteArray() {
        assert classEnded;
        if (!classEnded) {
            return null;
        }

        return cw.toByteArray();
    }

    /**
     * Abstraction for flags used in class emission
     *
     * We provide abstraction separating these from the underlying bytecode
     * emitter.
     *
     * Flags are provided for method handles, protection levels, static/virtual
     * fields/methods.
     */
    public static enum Flag {
        /** method handle with static access */
        HANDLE_STATIC(H_INVOKESTATIC),
        /** method handle with new invoke special access */
        HANDLE_NEWSPECIAL(H_NEWINVOKESPECIAL),
        /** method handle with invoke special access */
        HANDLE_SPECIAL(H_INVOKESPECIAL),
        /** method handle with invoke virtual access */
        HANDLE_VIRTUAL(H_INVOKEVIRTUAL),
        /** method handle with invoke interface access */
        HANDLE_INTERFACE(H_INVOKEINTERFACE),

        /** final access */
        FINAL(ACC_FINAL),
        /** static access */
        STATIC(ACC_STATIC),
        /** public access */
        PUBLIC(ACC_PUBLIC),
        /** private access */
        PRIVATE(ACC_PRIVATE);

        private int value;

        private Flag(final int value) {
            this.value = value;
        }

        /**
         * Get the value of this flag
         * @return the int value
         */
        public int getValue() {
            return value;
        }

        /**
         * Return the corresponding ASM flag value for an enum set of flags
         *
         * @param flags enum set of flags
         * @return an integer value representing the flags intrinsic values or:ed together
         */
        public static int getValue(final EnumSet<Flag> flags) {
            int v = 0;
            for (final Flag flag : flags) {
                v |= flag.getValue();
            }
            return v;
        }
    }

}