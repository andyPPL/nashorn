/*
 * Copyright (c) 2010, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/**
 * JDK-8006529 : Methods should not always get callee parameter, and they
 * should not be too eager in creation of scopes.
 *
 * @test
 * @run
 */

/*
 * This test script depends on nashorn Compiler internals. It uses reflection
 * to get access to private field and many public methods of Compiler and
 * FunctionNode classes. Note that this is trusted code and access to such
 * internal package classes and methods is okay. But, if you modify any
 * Compiler or FunctionNode class, you may have to revisit this script.
 * We cannot use direct Java class (via dynalink bean linker) to Compiler
 * and FunctionNode because of package-access check and so reflective calls.
 */
var Reflector           = Java.type("org.openjdk.nashorn.test.models.Reflector");
var forName             = java.lang.Class["forName(String)"];
var Parser              = forName("org.openjdk.nashorn.internal.parser.Parser").static
var Compiler            = forName("org.openjdk.nashorn.internal.codegen.Compiler").static
var CompilationPhases   = forName("org.openjdk.nashorn.internal.codegen.Compiler$CompilationPhases").static;
var Context             = forName("org.openjdk.nashorn.internal.runtime.Context").static
var CodeInstaller       = forName("org.openjdk.nashorn.internal.runtime.CodeInstaller").static
var ScriptEnvironment   = forName("org.openjdk.nashorn.internal.runtime.ScriptEnvironment").static
var Source              = forName("org.openjdk.nashorn.internal.runtime.Source").static
var FunctionNode        = forName("org.openjdk.nashorn.internal.ir.FunctionNode").static
var Block               = forName("org.openjdk.nashorn.internal.ir.Block").static
var VarNode             = forName("org.openjdk.nashorn.internal.ir.VarNode").static
var ExpressionStatement = forName("org.openjdk.nashorn.internal.ir.ExpressionStatement").static
var UnaryNode           = forName("org.openjdk.nashorn.internal.ir.UnaryNode").static
var BinaryNode          = forName("org.openjdk.nashorn.internal.ir.BinaryNode").static
var ThrowErrorManager   = forName("org.openjdk.nashorn.internal.runtime.Context$ThrowErrorManager").static
var ErrorManager        = forName("org.openjdk.nashorn.internal.runtime.ErrorManager").static
var Debug               = forName("org.openjdk.nashorn.internal.runtime.Debug").static
var String              = forName("java.lang.String").static
var boolean             = Java.type("boolean");

var parseMethod = Parser.class.getMethod("parse");
var compileMethod = Compiler.class.getMethod("compile", FunctionNode.class, CompilationPhases.class);
var getBodyMethod = FunctionNode.class.getMethod("getBody");
var getStatementsMethod = Block.class.getMethod("getStatements");
var getInitMethod = VarNode.class.getMethod("getInit");
var getExpressionMethod = ExpressionStatement.class.getMethod("getExpression")
var rhsMethod = UnaryNode.class.getMethod("getExpression")
var lhsMethod = BinaryNode.class.getMethod("lhs")
var binaryRhsMethod = BinaryNode.class.getMethod("rhs")
var debugIdMethod = Debug.class.getMethod("id", java.lang.Object.class)
var compilePhases = Reflector.get(CompilationPhases.class.getField("COMPILE_UPTO_BYTECODE"), null);

function invoke(m, obj) {
    return Reflector.invoke(m, obj);
}

// These are method names of methods in FunctionNode class
var allAssertionList = ['isVarArg', 'needsParentScope', 'needsCallee', 'hasScopeBlock', 'usesSelfSymbol', 'isSplit', 'hasEval', 'allVarsInScope', 'isStrict']

// corresponding Method objects of FunctionNode class
var functionNodeMethods = {};
// initialize FunctionNode methods
(function() {
    for (var f in allAssertionList) {
        var method = allAssertionList[f];
        functionNodeMethods[method] = FunctionNode.class.getMethod(method);
    }
})();

// returns functionNode.getBody().getStatements().get(0)
function getFirstFunction(functionNode) {
    var f = findFunction(invoke(getBodyMethod, functionNode))
    if (f == null) {
        throw new Error();
    }
    return f;
}

function findFunction(node) {
    if(node instanceof Block) {
        var stmts = invoke(getStatementsMethod, node)
        for(var i = 0; i < stmts.size(); ++i) {
            var retval = findFunction(stmts.get(i))
            if(retval != null) {
                return retval;
            }
        }
    } else if(node instanceof VarNode) {
        return findFunction(invoke(getInitMethod, node))
    } else if(node instanceof UnaryNode) {
        return findFunction(invoke(rhsMethod, node))
    } else if(node instanceof BinaryNode) {
        return findFunction(invoke(lhsMethod, node)) || findFunction(invoke(binaryRhsMethod, node))
    } else if(node instanceof ExpressionStatement) {
        return findFunction(invoke(getExpressionMethod, node))
    } else if(node instanceof FunctionNode) {
        return node
    }
}

var getContextMethod = Context.class.getMethod("getContext")
var getEnvMethod = Context.class.getMethod("getEnv")

var sourceForMethod = Source.class.getMethod("sourceFor", java.lang.String.class, java.lang.String.class)
var ParserConstructor = Parser.class.getConstructor(ScriptEnvironment.class, Source.class, ErrorManager.class)
var CompilerConstructor = Compiler.class.getMethod("forNoInstallerCompilation", Context.class, Source.class, boolean.class);

// compile(script) -- compiles a script specified as a string with its
// source code, returns a org.openjdk.nashorn.internal.ir.FunctionNode object
// representing it.
function compile(source, phases) {
    var source = sourceForMethod.invoke(null, "<no name>", source);

    var ctxt = getContextMethod.invoke(null);
    var env = getEnvMethod.invoke(ctxt);

    var parser   = Reflector.newInstance(ParserConstructor, env, source, ThrowErrorManager.class.newInstance());
    var func     = invoke(parseMethod, parser);

    var compiler = Reflector.invoke(CompilerConstructor, null, ctxt, source, false);

    return Reflector.invoke(compileMethod, compiler, func, phases);
};

var allAssertions = (function() {
    var allAssertions = {}
    for(var assertion in allAssertionList) {
        allAssertions[allAssertionList[assertion]] = true
    }
    return allAssertions;
})();


// test(f[, assertions...]) tests whether all the specified assertions on the
// passed function node are true.
function test(f) {
    var assertions = {}
    for(var i = 1; i < arguments.length; ++i) {
        var assertion = arguments[i]
        if(!allAssertions[assertion]) {
            throw "Unknown assertion " + assertion + " for " + f;
        }
        assertions[assertion] = true
    }
    for(var assertion in allAssertions) {
        var expectedValue = !!assertions[assertion]
        var actualValue = invoke(functionNodeMethods[assertion], f)
        if(actualValue !== expectedValue) {
            throw "Expected " + assertion + " === " + expectedValue + ", got " + actualValue + " for " + f + ":"
                + invoke(debugIdMethod, null, f);
        }
    }
}

// testFirstFn(script[, assertions...] tests whether all the specified
// assertions are true in the first function in the given script; "script"
// is a string with the source text of the script.
function testFirstFn(script) {
    arguments[0] = getFirstFunction(compile(script, compilePhases));
    test.apply(null, arguments);
}

// ---------------------------------- ACTUAL TESTS START HERE --------------

// The simplest possible functions have no attributes set
testFirstFn("function f() { }")
testFirstFn("function f(x) { x }")

// A function referencing a global needs parent scope, and it needs callee
// (because parent scope is passed through callee)
testFirstFn("function f() { x }", 'needsCallee', 'needsParentScope')

// A function referencing "arguments" will have to be vararg. It also needs
// the callee, as it needs to fill out "arguments.callee".
testFirstFn("function f() { arguments }", 'needsCallee', 'isVarArg')

// A function referencing "arguments" will have to be vararg. If it is
// strict, it will not have to have a callee, though.
testFirstFn("function f() {'use strict'; arguments }", 'isVarArg', 'isStrict')

// A function defining "arguments" as a parameter will not be vararg.
testFirstFn("function f(arguments) { arguments }")

// A function defining "arguments" as a nested function will not be vararg.
testFirstFn("function f() { function arguments() {}; arguments; }")

// A function defining "arguments" as a local variable will be vararg.
testFirstFn("function f() { var arguments; arguments; }", 'isVarArg', 'needsCallee')

// A self-referencing function defined as a statement doesn't need a self
// symbol, as it'll rather obtain itself from the parent scope.
testFirstFn("function f() { f() }", 'needsCallee', 'needsParentScope')

// A self-referencing function defined as an expression needs a self symbol,
// as it can't obtain itself from the parent scope.
testFirstFn("(function f() { f() })", 'needsCallee', 'usesSelfSymbol')

// A child function accessing parent's variable triggers the need for scope
// in parent
testFirstFn("(function f() { var x; function g() { x } })", 'hasScopeBlock')

// A child function accessing parent's parameter triggers the need for scope
// in parent
testFirstFn("(function f(x) { function g() { x } })", 'hasScopeBlock')

// A child function accessing a global variable triggers the need for parent
// scope in parent
testFirstFn("(function f() { function g() { x } })", 'needsParentScope', 'needsCallee')

// A child function redefining a local variable from its parent should not
// affect the parent function in any way
testFirstFn("(function f() { var x; function g() { var x; x } })")

// Using "with" on its own doesn't do much.
testFirstFn("(function f() { var o; with(o) {} })")

// "with" referencing a local variable triggers scoping.
testFirstFn("(function f() { var x; var y; with(x) { y } })", 'hasScopeBlock')

// "with" referencing a non-local variable triggers parent scope.
testFirstFn("(function f() { var x; with(x) { y } })", 'needsCallee', 'needsParentScope')

// Nested function using "with" is pretty much the same as the parent
// function needing with.
testFirstFn("(function f() { function g() { var o; with(o) {} } })")

// Nested function using "with" referencing a local variable.
testFirstFn("(function f() { var x; function g() { var o; with(o) { x } } })", 'hasScopeBlock')

// Using "eval" triggers pretty much everything. The function even needs to be
// vararg, 'cause we don't know if eval will be using "arguments".
testFirstFn("(function f() { eval() })", 'usesSelfSymbol', 'needsParentScope', 'needsCallee', 'hasScopeBlock', 'hasEval', 'isVarArg', 'allVarsInScope')

// Nested function using "eval" is almost the same as parent function using
// eval, but at least the parent doesn't have to be vararg.
testFirstFn("(function f() { function g() { eval() } })", 'usesSelfSymbol', 'needsParentScope', 'needsCallee', 'hasScopeBlock', 'allVarsInScope')

// Function with 125 named parameters is ordinary
testFirstFn("function f(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45, p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60, p61, p62, p63, p64, p65, p66, p67, p68, p69, p70, p71, p72, p73, p74, p75, p76, p77, p78, p79, p80, p81, p82, p83, p84, p85, p86, p87, p88, p89, p90, p91, p92, p93, p94, p95, p96, p97, p98, p99, p100, p101, p102, p103, p104, p105, p106, p107, p108, p109, p110, p111, p112, p113, p114, p115, p116, p117, p118, p119, p120, p121, p122, p123, p124, p125) { p125 = p124 }")

// Function with 126 named parameters is variable arguments
// NOTE: hasScopeBlock should be optimized away. Implementation of JDK-8038942 should take care of it.
testFirstFn("function f(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45, p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60, p61, p62, p63, p64, p65, p66, p67, p68, p69, p70, p71, p72, p73, p74, p75, p76, p77, p78, p79, p80, p81, p82, p83, p84, p85, p86, p87, p88, p89, p90, p91, p92, p93, p94, p95, p96, p97, p98, p99, p100, p101, p102, p103, p104, p105, p106, p107, p108, p109, p110, p111, p112, p113, p114, p115, p116, p117, p118, p119, p120, p121, p122, p123, p124, p125, p126) { p125 = p126 }", 'isVarArg', 'hasScopeBlock')
