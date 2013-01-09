/*
 * DO NOT REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 ForgeRock Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

package org.forgerock.script.javascript;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;

import org.forgerock.script.engine.CompiledScript;
import org.forgerock.script.exception.ScriptThrownException;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrappedException;
import org.mozilla.javascript.tools.shell.Global;
import org.mozilla.javascript.tools.shell.QuitAction;

/**
 * A JavaScript script.
 * <p>
 * This implementation pre-compiles the provided script. Any syntax errors in
 * the source code will throw an exception during construction of the object.
 * <p>
 * 
 * @author Paul C. Bryan
 * @author aegloff
 */
public class RhinoScript implements CompiledScript {

    /**
     * A sealed shared scope to improve performance; avoids allocating standard
     * objects on every exec call.
     */
    private static ScriptableObject SHARED_SCOPE = null; // lazily initialized

    /** The script level scope to use */
    private Scriptable scriptScope = null;

    /** The compiled script to execute. */
    private final Script script;

    /** The script name */
    private String scriptName;

    /** Default ScripContext */
    //private final ScriptContext scriptContext;

    public static Global global = new Global();

    static {
        global.initQuitAction(new IProxy());
    }

    /**
     * Proxy class to avoid proliferation of anonymous classes.
     */
    private static class IProxy implements QuitAction {
        public void quit(Context cx, int exitCode) {
            /* no quit :) */
        }
    }

    /** Indicates if this script instance should use the shared scope. */
    private final boolean sharedScope;

    /**
     * Compiles the JavaScript source code into an executable script. A sealed
     * shared scope containing standard JavaScript objects (Object, String,
     * Number, Date, etc.) will be used for script execution rather than
     * allocating a new unsealed scope for each execution.
     * 
     * @param source
     *            the source code of the JavaScript script.
     * @throws ScriptException
     *             if there was an exception encountered while compiling the
     *             script.
     */
    public RhinoScript(String name, String source, ScriptContext scriptContext)
            throws ScriptException {
        this(name, source, true);
    }

    /**
     * Compiles the JavaScript source code into an executable script. If
     * {@code useSharedScope} is {@code true}, then a sealed shared scope
     * containing standard JavaScript objects (Object, String, Number, Date,
     * etc.) will be used for script execution; otherwise a new unsealed scope
     * will be allocated for each execution.
     * 
     * @param source
     *            the source code of the JavaScript script.
     * @param sharedScope
     *            if {@code true}, uses the shared scope, otherwise allocates
     *            new scope.
     * @throws ScriptException
     *             if there was an exception encountered while compiling the
     *             script.
     */
    public RhinoScript(String name, String source, boolean sharedScope)
            throws ScriptException {
        this.scriptName = name;
        this.sharedScope = sharedScope;
        Context cx = Context.enter();
        try {
            scriptScope = getScriptScope(cx);
            script = cx.compileString(source, name, 1, null);
        } catch (RhinoException re) {
            throw new ScriptException(re.getMessage());
        } finally {
            Context.exit();
        }
    }

    /**
     * TEMPORARY
     */
    public RhinoScript(String name, Reader reader, boolean sharedScope)
            throws ScriptException {
        this.scriptName = name;
        this.sharedScope = sharedScope;
        try {
            Context cx = Context.enter();
            try {
                scriptScope = getScriptScope(cx);
                script = cx.compileReader(reader, name, 1, null);
            } catch (RhinoException re) {
                throw new ScriptException(re);
            } finally {
                Context.exit();
            }
        } catch (IOException ioe) {
            throw new ScriptException(ioe);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // meaningless exception
                }
            }
        }
    }

    /**
     * Gets the JavaScript standard objects, either as the shared sealed scope
     * or as a newly allocated set of standard objects, depending on the value
     * of {@code useSharedScope}.
     * 
     * @param context
     *            The runtime context of the executing script.
     * @return the JavaScript standard objects.
     */
    private ScriptableObject getStandardObjects(Context context) {
        if (!sharedScope) {
            ScriptableObject scope = context.initStandardObjects(); // somewhat
                                                                    // expensive
            return scope;
        }
        if (SHARED_SCOPE == null) { // lazy initialization race condition is
                                    // harmless
            // ScriptableObject scope = context.initStandardObjects(null, true);
            ScriptableObject scope = new Global(context);
            scope.sealObject(); // seal the whole scope (not just standard
                                // objects)
            SHARED_SCOPE = scope;
        }
        return SHARED_SCOPE;
    }

    /**
     * Get the scope scriptable re-used for this script Holds common
     * functionality such as the logger
     * 
     * @param context
     *            The runtime context of the executing script.
     * @return the context scriptable for this script
     */
    private Scriptable getScriptScope(Context context) {
        Map<String, Object> scriptScopeMap = new HashMap<String, Object>();
        addLoggerProperty(scriptScopeMap);
        Scriptable scriptScopeScriptable = new ScriptableMap(null, scriptScopeMap);
        scriptScopeScriptable.setPrototype(getStandardObjects(context)); // standard
                                                                         // objects
                                                                         // included
                                                                         // with
                                                                         // every
                                                                         // box
        scriptScopeScriptable.setParentScope(null);
        return scriptScopeScriptable;
    }

    /**
     * Add the logger property to the JavaScript scope
     * 
     * @param scope
     *            to add the property to
     */
    private void addLoggerProperty(Map<String, Object> scope) {
        String loggerName = "org.forgerock.openidm.script.javascript.JavaScript." + scriptName;
        //scope.put("logger", LoggerPropertyFactory.get(loggerName));
    }

    public Object eval(org.forgerock.json.resource.Context requestContext, Bindings bindings) throws ScriptException {
        if (bindings == null) {
            throw new NullPointerException();
        }
        Context context = Context.enter();
        try {
            //TODO missing Context and ConnectionFactory
            Scriptable outer = new ScriptableMap(null, bindings);
            outer.setPrototype(scriptScope); // script level context and
                                             // standard objects included with
                                             // every box
            outer.setParentScope(null);
            Scriptable inner = context.newObject(outer); // inner transient
                                                         // scope for new
                                                         // properties
            inner.setPrototype(outer);
            inner.setParentScope(null);
            Object result = Converter.convert(script.exec(context, inner));
            return result;
        } catch (WrappedException e) {
            //TODO Implement properly
            if ( e.getWrappedException() instanceof NoSuchMethodException) {
                throw new ScriptThrownException("NoSuchMethodException",(Exception)e.getWrappedException());
            } else if (e.getWrappedException() instanceof Exception) {
                throw new ScriptThrownException("Exception",(Exception)e.getWrappedException());
            } else {
                throw new ScriptThrownException("Throwable",e.getWrappedException().getMessage());
            }
        } catch (RhinoException re) {
            if (re instanceof JavaScriptException) { // thrown by the script
                                                     // itself
                throw new ScriptThrownException(Converter.convert(((JavaScriptException) re)
                        .getValue()), re);
            } else { // some other runtime exception encountered
                throw new ScriptException(re.getMessage());
            }
        } finally {
            Context.exit();
        }
    }
}
