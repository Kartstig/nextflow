/*
 * Copyright 2013-2023, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.ast

import static nextflow.Const.*
import static nextflow.ast.ASTHelpers.*
import static org.codehaus.groovy.ast.tools.GeneralUtils.*

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.NF
import nextflow.script.BaseScript
import nextflow.script.BodyDef
import nextflow.script.IncludeDef
import nextflow.script.TaskClosure
import nextflow.script.TokenEnvCall
import nextflow.script.TokenFileCall
import nextflow.script.TokenPathCall
import nextflow.script.TokenStdinCall
import nextflow.script.TokenStdoutCall
import nextflow.script.TokenValCall
import nextflow.script.TokenValRef
import nextflow.script.TokenVar
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
/**
 * Implement some syntax sugars of Nextflow DSL scripting.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

@Slf4j
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.CONVERSION)
class NextflowDSLImpl implements ASTTransformation {

    final static private String WORKFLOW_TAKE = 'take'
    final static private String WORKFLOW_EMIT = 'emit'
    final static private String WORKFLOW_MAIN = 'main'
    final static private List<String> SCOPES = [WORKFLOW_TAKE, WORKFLOW_EMIT, WORKFLOW_MAIN]

    final static public String PROCESS_WHEN = 'when'
    final static public String PROCESS_STUB = 'stub'

    static public String OUT_PREFIX = '$out'

    static private Set<String> RESERVED_NAMES

    static {
        // method names implicitly defined by the groovy script SHELL
        RESERVED_NAMES = ['main','run','runScript'] as Set
        // existing method cannot be used for custom script definition
        for( def method : BaseScript.getMethods() ) {
            RESERVED_NAMES.add(method.name)
        }

    }

    @Override
    void visit(ASTNode[] astNodes, SourceUnit unit) {
        createVisitor(unit).visitClass((ClassNode)astNodes[1])
    }

    /*
     * create the code visitor
     */
    protected ClassCodeVisitorSupport createVisitor( SourceUnit unit ) {
        new DslCodeVisitor(unit)
    }

    @CompileStatic
    static class DslCodeVisitor extends ClassCodeVisitorSupport {


        final private SourceUnit unit

        private String currentLabel

        private String bodyLabel

        private Set<String> processNames = []

        private Set<String> workflowNames = []

        private Set<String> functionNames = []

        private int anonymousWorkflow

        protected SourceUnit getSourceUnit() { unit }


        DslCodeVisitor(SourceUnit unit) {
            this.unit = unit
        }

        @Override
        void visitMethod(MethodNode node) {
            if( node.public && !node.static && !node.synthetic && !node.metaDataMap?.'org.codehaus.groovy.ast.MethodNode.isScriptBody') {
                if( !isIllegalName(node.name, node))
                    functionNames.add(node.name)
            }
            super.visitMethod(node)
        }

        @Override
        void visitMethodCallExpression(MethodCallExpression methodCall) {
            // pre-condition to be verified to apply the transformation
            final preCondition = methodCall.objectExpression?.getText() == 'this'
            final methodName = methodCall.getMethodAsString()

            /*
             * intercept the *process* method in order to transform the script closure
             */
            if( methodName == 'process' && preCondition ) {

                // clear block label
                bodyLabel = null
                currentLabel = null
                convertProcessDef(methodCall,sourceUnit)
                super.visitMethodCallExpression(methodCall)
            }
            else if( methodName == 'workflow' && preCondition ) {
                convertWorkflowDef(methodCall,sourceUnit)
                super.visitMethodCallExpression(methodCall)
            }

            // just apply the default behavior
            else {
                super.visitMethodCallExpression(methodCall)
            }

        }

        @Override
        void visitExpressionStatement(ExpressionStatement stm) {
            if( stm.text.startsWith('this.include(') && stm.getExpression() instanceof MethodCallExpression )  {
                final methodCall = (MethodCallExpression)stm.getExpression()
                convertIncludeDef(methodCall)
                // this is necessary to invoke the `load` method on the include definition
                final loadCall = new MethodCallExpression(methodCall, 'load0', new ArgumentListExpression(new VariableExpression('params')))
                stm.setExpression(loadCall)
            }
            super.visitExpressionStatement(stm)
        }

        protected void convertIncludeDef(MethodCallExpression call) {
            if( call.methodAsString=='include' && call.arguments instanceof ArgumentListExpression ) {
                final allArgs = (ArgumentListExpression)call.arguments
                if( allArgs.size() != 1 || allArgs[0] !instanceof ClosureExpression ) {
                    syntaxError(call, "Not a valid include statement -- the correct syntax is `include { ... } from '...'`")
                    return
                }

                // extract module arguments from closure
                final arg = (ClosureExpression)allArgs[0]
                final block = (BlockStatement)arg.code
                final modulesList = new ListExpression()
                for( Statement stm : block.statements ) {
                    if( stm instanceof ExpressionStatement ) {
                        CastExpression castX
                        VariableExpression varX
                        Expression moduleX

                        // extract module name, e.g. `foo`
                        if( (varX=isVariableX(stm.expression)) ) {
                            def name = constX(varX.name)
                            moduleX = createX(IncludeDef.Module, name)
                        }
                        // extract module name with alias, e.g. `foo as bar`
                        else if( (castX=isCastX(stm.expression)) && (varX=isVariableX(castX.expression)) ) {
                            def name = constX(varX.name)
                            final alias = constX(castX.type.name)
                            moduleX = createX(IncludeDef.Module, name, alias)
                        }
                        // otherwise return an error
                        else {
                            syntaxError(call, "Not a valid include module name")
                            return
                        }
                        modulesList.addExpression(moduleX)
                    }
                    else {
                        syntaxError(call, "Not a valid include module name")
                        return
                    }
                }

                // replace include() argument with IncludeDef instance
                call.setArguments(new ArgumentListExpression( createX(IncludeDef, modulesList) ))
            }

            // skip outer method calls e.g. `from`, `addParams`
            else if( call.objectExpression instanceof MethodCallExpression ) {
                convertIncludeDef((MethodCallExpression)call.objectExpression)
            }
        }

        /*
         * this method transforms the DSL definition
         *
         *   workflow foo {
         *     code
         *   }
         *
         * into a method invocation as
         *
         *   workflow('foo', { -> code })
         *
         */
        protected void convertWorkflowDef(MethodCallExpression methodCall, SourceUnit unit) {
            log.trace "Convert 'workflow' ${methodCall.arguments}"

            assert methodCall.arguments instanceof ArgumentListExpression
            def args = (ArgumentListExpression)methodCall.arguments
            def len = args.size()

            // anonymous workflow definition
            if( len == 1 && args[0] instanceof ClosureExpression ) {
                if( anonymousWorkflow++ > 0 ) {
                    unit.addError( new SyntaxException("Duplicate entry workflow definition", methodCall.lineNumber, methodCall.columnNumber+8))
                    return
                }

                def newArgs = new ArgumentListExpression()
                def body = (ClosureExpression)args[0]
                newArgs.addExpression( makeWorkflowDefWrapper(body,true) )
                methodCall.setArguments( newArgs )
                return 
            }

            // extract the first argument which has to be a method-call expression
            // the name of this method represent the *workflow* name
            if( len != 1 || !args[0].class.isAssignableFrom(MethodCallExpression) ) {
                log.debug "Missing name in workflow definition at line: ${methodCall.lineNumber}"
                unit.addError( new SyntaxException("Workflow definition syntax error -- A string identifier must be provided after the `workflow` keyword", methodCall.lineNumber, methodCall.columnNumber+8))
                return
            }

            final nested = args[0] as MethodCallExpression
            final name = nested.getMethodAsString()
            // check the process name is not defined yet
            if( isIllegalName(name, methodCall) ) {
                return
            }
            workflowNames.add(name)

            // the nested method arguments are the arguments to be passed
            // to the process definition, plus adding the process *name*
            // as an extra item in the arguments list
            args = (ArgumentListExpression)nested.getArguments()
            len = args.size()
            log.trace "Workflow name: $name with args: $args"

            // make sure to add the 'name' after the map item
            // (which represent the named parameter attributes)
            def newArgs = new ArgumentListExpression()

            // add the workflow body def
            if( len != 1 || !(args[0] instanceof ClosureExpression)) {
                syntaxError(methodCall, "Invalid workflow definition")
                return
            }

            final body = (ClosureExpression)args[0]
            newArgs.addExpression( constX(name) )
            newArgs.addExpression( makeWorkflowDefWrapper(body,false) )

            // set the new list as the new arguments
            methodCall.setArguments( newArgs )
        }


        protected Statement normWorkflowParam(ExpressionStatement stat, String type, Set<String> uniqueNames, List<Statement> body) {
            MethodCallExpression callx
            VariableExpression varx

            if( (callx=isMethodCallX(stat.expression)) && isThisX(callx.objectExpression) ) {
                final name = "_${type}_${callx.methodAsString}"
                return stmt( callThisX(name, callx.arguments) )
            }

            if( (varx=isVariableX(stat.expression)) ) {
                final name = "_${type}_${varx.name}"
                return stmt( callThisX(name) )
            }

            if( type == WORKFLOW_EMIT ) {
                return createAssignX(stat, body, type, uniqueNames)
            }

            syntaxError(stat, "Workflow malformed parameter definition")
            return stat
        }

        protected Statement createAssignX(ExpressionStatement stat, List<Statement> body, String type, Set<String> uniqueNames) {
            BinaryExpression binx
            MethodCallExpression callx
            Expression args=null

            if( (binx=isAssignX(stat.expression)) ) {
                // keep the statement in body to allow it to be evaluated
                body.add(stat)
                // and create method call expr to capture the var name in the emission
                final left = (VariableExpression)binx.leftExpression
                final name = "_${type}_${left.name}"
                return stmt( callThisX(name) )
            }

            if( (callx=isMethodCallX(stat.expression)) && callx.objectExpression.text!='this' && hasTo(callx)) {
                // keep the args
                args = callx.arguments
                // replace the method call expression with a property
                stat.expression = new PropertyExpression(callx.objectExpression, callx.method)
                // then, fallback to default case
            }

            // wrap the expression into a assignment expression
            final var = getNextName(uniqueNames)
            final left = new VariableExpression(var)
            final right = stat.expression
            final token = new Token(Types.ASSIGN, '=', -1, -1)
            final assign = new BinaryExpression(left, token, right)
            body.add(stmt(assign))

            // the call method statement for the emit declaration
            final name="_${type}_${var}"
            callx =  args ? callThisX(name, args) : callThisX(name)
            return stmt(callx)
        }

        protected boolean hasTo(MethodCallExpression callX) {
            def tupleX = isTupleX(callX.arguments)
            if( !tupleX ) return false
            if( !tupleX.expressions ) return false
            def mapX = isMapX(tupleX.expressions[0])
            if( !mapX ) return false
            def entry = mapX.getMapEntryExpressions().find { isConstX(it.keyExpression).text=='to' }
            return entry != null
        }

        protected String getNextName(Set<String> allNames) {
            String result
            while( true ) {
                result = OUT_PREFIX + allNames.size()
                if( allNames.add(result) )
                    break
            }
            return result
        }

        protected Expression makeWorkflowDefWrapper( ClosureExpression closure, boolean anonymous ) {

            final codeBlock = (BlockStatement) closure.code
            final codeStms = codeBlock.statements
            final scope = codeBlock.variableScope

            final visited = new HashMap<String,Boolean>(5);
            final emitNames = new LinkedHashSet<String>(codeStms.size())
            final wrap = new ArrayList<Statement>(codeStms.size())
            final body = new ArrayList<Statement>(codeStms.size())
            final source = new StringBuilder()
            String context = null
            String previous = null
            for( Statement stm : codeStms ) {
                previous = context
                context = stm.statementLabel ?: context
                // check for changing context
                if( context && context != previous ) {
                    if( visited[context] && visited[previous] ) {
                        syntaxError(stm, "Unexpected workflow `${context}` context here")
                        break
                    }
                }
                visited[context] = true

                switch (context) {
                    case WORKFLOW_TAKE:
                    case WORKFLOW_EMIT:
                        if( !(stm instanceof ExpressionStatement) ) {
                            syntaxError(stm, "Workflow malformed parameter definition")
                            break
                        }
                        wrap.add(normWorkflowParam(stm as ExpressionStatement, context, emitNames, body))
                    break

                    case WORKFLOW_MAIN:
                        body.add(stm)
                        break

                    default:
                        if( context ) {
                            def opts = SCOPES.closest(context)
                            def msg = "Unknown execution scope '$context:'"
                            if( opts ) msg += " -- Did you mean ${opts.collect{"'$it'"}.join(', ')}"
                            syntaxError(stm, msg)
                        }
                        body.add(stm)
                }
            }
            // read the closure source
            readSource(closure, source, unit, true)

            final bodyClosure = closureX(null, block(scope, body))
            final invokeBody = makeScriptWrapper(bodyClosure, source.toString(), 'workflow', unit)
            wrap.add( stmt(invokeBody) )

            closureX(null, block(scope, wrap))
        }

        protected void syntaxError(ASTNode node, String message) {
            int line = node.lineNumber
            int coln = node.columnNumber
            unit.addError( new SyntaxException(message,line,coln))
        }

        /**
         * Transform a DSL `process` definition into a proper method invocation
         *
         * @param methodCall
         * @param unit
         */
        protected void convertProcessBlock( MethodCallExpression methodCall, SourceUnit unit ) {
            log.trace "Apply task closure transformation to method call: $methodCall"

            final args = methodCall.arguments as ArgumentListExpression
            final lastArg = args.expressions.size()>0 ? args.getExpression(args.expressions.size()-1) : null

            // the block holding all the statements defined in the process (closure) definition
            final block = (lastArg as ClosureExpression).code as BlockStatement

            /*
             * iterate over the list of statements to:
             * - converts the method after the 'input:' label as input parameters
             * - converts the method after the 'output:' label as output parameters
             * - collect all the statement after the 'exec:' label
             */
            def source = new StringBuilder()
            List<Statement> execStatements = []

            List<Statement> whenStatements = []
            def whenSource = new StringBuilder()

            List<Statement> stubStatements = []
            def stubSource = new StringBuilder()


            def iterator = block.getStatements().iterator()
            while( iterator.hasNext() ) {

                // get next statement
                Statement stm = iterator.next()

                // keep track of current block label
                currentLabel = stm.statementLabel ?: currentLabel

                switch(currentLabel) {
                    case 'input':
                        if( stm instanceof ExpressionStatement ) {
                            fixLazyGString( stm )
                            fixStdinStdout( stm )
                            convertInputMethod( stm.getExpression() )
                        }
                        break

                    case 'output':
                        if( stm instanceof ExpressionStatement ) {
                            fixLazyGString( stm )
                            fixStdinStdout( stm )
                            convertOutputMethod( stm.getExpression() )
                        }
                        break

                    case 'exec':
                        bodyLabel = currentLabel
                        iterator.remove()
                        execStatements << stm
                        readSource(stm,source,unit)
                        break

                    case 'script':
                    case 'shell':
                        bodyLabel = currentLabel
                        iterator.remove()
                        execStatements << stm
                        readSource(stm,source,unit)
                        break

                    case PROCESS_STUB:
                        iterator.remove()
                        stubStatements << stm
                        readSource(stm,stubSource,unit)
                        break

                    // capture the statements in a when guard and remove from the current block
                    case PROCESS_WHEN:
                        if( iterator.hasNext() ) {
                            iterator.remove()
                            whenStatements << stm
                            readSource(stm,whenSource,unit)
                            break
                        }
                        // when entering in this branch means that this is the last statement,
                        // which is supposed to be the task command
                        // hence if no previous `when` statement has been processed, a syntax error is returned
                        else if( !whenStatements ) {
                            int line = methodCall.lineNumber
                            int coln = methodCall.columnNumber
                            unit.addError(new SyntaxException("Invalid process definition -- Empty `when` or missing `script` statement", line, coln))
                            return
                        }
                        else
                            break

                    default:
                        if(currentLabel) {
                            def line = stm.getLineNumber()
                            def coln = stm.getColumnNumber()
                            unit.addError(new SyntaxException("Invalid process definition -- Unknown keyword `$currentLabel`",line,coln))
                            return
                        }

                        fixLazyGString(stm)
                        fixDirectiveWithNegativeValue(stm)  // Fixes #180
                }
            }

            /*
             * add the `when` block if found
             */
            if( whenStatements ) {
                addWhenGuardCall(whenStatements, whenSource, block)
            }

            /*
             * add try `stub` block if found
             */
            if( stubStatements ) {
                final newBLock = addStubCall(stubStatements, stubSource, block)
                newBLock.visit(new TaskCmdXformVisitor(unit))
            }

            /*
             * wrap all the statements after the 'exec:'  label by a new closure containing them (in a new block)
             */
            final len = block.statements.size()
            boolean done = false
            if( execStatements ) {
                // create a new Closure
                def execBlock = new BlockStatement(execStatements, new VariableScope(block.variableScope))
                def execClosure = new ClosureExpression( Parameter.EMPTY_ARRAY, execBlock )

                // append the new block to the
                // set the 'script' flag parameter
                def wrap = makeScriptWrapper(execClosure, source, bodyLabel, unit)
                block.addStatement( new ExpressionStatement(wrap) )
                if( bodyLabel == 'script' )
                    block.visit(new TaskCmdXformVisitor(unit))
                done = true

            }
            // when only the `stub` block is defined add an empty command
            else if ( !bodyLabel && stubStatements ) {
                final cmd = 'true'
                final list = new ArrayList<Statement>(1);
                list.add( new ExpressionStatement(constX(cmd)) )
                final dummyBlock = new BlockStatement( list, new VariableScope(block.variableScope))
                final dummyClosure = new ClosureExpression( Parameter.EMPTY_ARRAY, dummyBlock )

                // append the new block to the
                // set the 'script' flag parameter
                final wrap = makeScriptWrapper(dummyClosure, cmd, 'script', unit)
                block.addStatement( new ExpressionStatement(wrap) )
                done = true
            }

            /*
             * when the last statement is a string script, the 'script:' label can be omitted
             */
            else if( len ) {
                def stm = block.getStatements().get(len-1)
                readSource(stm,source,unit)

                if ( stm instanceof ReturnStatement  ){
                    done = wrapExpressionWithClosure(block, stm.getExpression(), len, source, unit)
                }

                else if ( stm instanceof ExpressionStatement )  {
                    done = wrapExpressionWithClosure(block, stm.getExpression(), len, source, unit)
                }

                // apply command variables escape
                stm.visit(new TaskCmdXformVisitor(unit))
            }

            if (!done) {
                log.trace "Invalid 'process' definition -- Process must terminate with string expression"
                int line = methodCall.lineNumber
                int coln = methodCall.columnNumber
                unit.addError( new SyntaxException("Invalid process definition -- Make sure the process ends with a script wrapped by quote characters",line,coln))
            }
        }

        /**
         * Converts a `when` block into a when method call expression. The when code is converted into a
         * closure expression and set a `when` directive in the process configuration properties.
         *
         * See {@link nextflow.script.ProcessConfig#configProperties}
         * See {@link nextflow.processor.TaskConfig#getGuard(java.lang.String)}
         */
        protected BlockStatement addWhenGuardCall( List<Statement> statements, StringBuilder source, BlockStatement parent ) {
            createBlock0(PROCESS_WHEN, statements, source, parent)
        }

        protected BlockStatement addStubCall(List<Statement> statements, StringBuilder source, BlockStatement parent ) {
            createBlock0(PROCESS_STUB, statements, source, parent)
        }

        protected BlockStatement createBlock0( String blockName, List<Statement> statements, StringBuilder source, BlockStatement parent ) {
            // wrap the code block into a closure expression
            def block = new BlockStatement(statements, new VariableScope(parent.variableScope))
            def closure = new ClosureExpression( Parameter.EMPTY_ARRAY, block )

            // the closure expression is wrapped itself into a TaskClosure object
            // in order to capture the closure source other than the closure code
            List<Expression> newArgs = []
            newArgs << closure
            newArgs << new ConstantExpression(source.toString())
            def whenObj = createX( TaskClosure, newArgs )

            // creates a method call expression for the method `when`
            def method = new MethodCallExpression(VariableExpression.THIS_EXPRESSION, blockName, whenObj)
            parent.getStatements().add(0, new ExpressionStatement(method))

            return block
        }

        /**
         * Wrap the user provided piece of code, either a script or a closure with a {@code BodyDef} object
         *
         * @param closure
         * @param source
         * @param scriptOrNative
         * @param unit
         * @return a {@code BodyDef} object
         */
        private Expression makeScriptWrapper( ClosureExpression closure, CharSequence source, String section, SourceUnit unit ) {

            final List<Expression> newArgs = []
            newArgs << (closure)
            newArgs << ( new ConstantExpression(source.toString()) )
            newArgs << ( new ConstantExpression(section) )

            // collect all variable tokens and pass them as single list argument
            final variables = fetchVariables(closure,unit)
            final listArg = new ArrayList(variables.size())
            for( TokenValRef var: variables ) {
                def pName = new ConstantExpression(var.name)
                def pLine = new ConstantExpression(var.lineNum)
                def pCol = new ConstantExpression(var.colNum)
                listArg << createX( TokenValRef, pName, pLine, pCol )
            }
            newArgs << ( new ListExpression(listArg) )

            // invokes the BodyDef constructor
            createX( BodyDef, newArgs )
        }

        /**
         * Read the user provided script source string
         *
         * @param node
         * @param buffer
         * @param unit
         */
        private void readSource( ASTNode node, StringBuilder buffer, SourceUnit unit, stripBrackets=false ) {
            final colx = node.getColumnNumber()
            final colz = node.getLastColumnNumber()
            final first = node.getLineNumber()
            final last = node.getLastLineNumber()
            for( int i=first; i<=last; i++ ) {
                def line = unit.source.getLine(i, null)
                if( i==last ) {
                    line = line.substring(0,colz-1)
                    if( stripBrackets ) {
                        line = line.replaceFirst(/}.*$/,'')
                        if( !line.trim() ) continue
                    }
                }
                if( i==first ) {
                    line = line.substring(colx-1)
                    if( stripBrackets ) {
                        line = line.replaceFirst(/^.*\{/,'').trim()
                        if( !line.trim() ) continue
                    }
                }
                buffer.append(line) .append('\n')
            }
        }

        protected void fixLazyGString( Statement stm ) {
            if( stm instanceof ExpressionStatement && stm.getExpression() instanceof MethodCallExpression ) {
                new GStringToLazyVisitor(unit).visitExpressionStatement(stm)
            }
        }

        protected void fixDirectiveWithNegativeValue( Statement stm ) {
            if( stm instanceof ExpressionStatement && stm.getExpression() instanceof BinaryExpression ) {
                def binary = (BinaryExpression)stm.getExpression()
                if(!(binary.leftExpression instanceof VariableExpression))
                    return
                if( binary.operation.type != Types.MINUS )
                    return

                // -- transform the binary expression into a method call expression
                //    where the left expression represents the method name to invoke
                def methodName = ((VariableExpression)binary.leftExpression).name

                // -- wrap the value into a minus operator
                def value = (Expression)new UnaryMinusExpression( binary.rightExpression )
                def args = new ArgumentListExpression( [value] )

                // -- create the method call expression and replace it to the binary expression
                def call = new MethodCallExpression(new VariableExpression('this'), methodName, args)
                stm.setExpression(call)

            }
        }

        protected void fixStdinStdout( ExpressionStatement stm ) {

            // transform the following syntax:
            //      `stdin`  --> `stdin()`
            //      `stdout` --> `stdout()`
            VariableExpression varX
            if( (varX=isVariableX(stm.expression)) && (varX.name=='stdin' || varX.name=='stdout') ) {
                final call = new MethodCallExpression( new VariableExpression('this'), varX.name, new ArgumentListExpression() )
                // remove replace the old one with the new one
                stm.setExpression(call)
            }
        }

        private static final VALID_INPUT_METHODS = ['val','env','file','path','stdin','each','tuple']

        protected void convertInputMethod( Expression expression ) {
            // don't throw error if not method because it could be an implicit script statement
            if( expression !instanceof MethodCallExpression )
                return

            def methodCall = expression as MethodCallExpression
            def methodName = methodCall.getMethodAsString()
            log.trace "convert > input method: $methodName"

            def caller = methodCall.objectExpression
            if( caller !instanceof VariableExpression || caller.getText() != 'this' ) {
                syntaxError(expression, "Invalid process input statement, possible syntax error")
                return
            }

            if( methodName !in VALID_INPUT_METHODS ) {
                syntaxError(expression, "Invalid process input method '${methodName}'")
                return
            }

            methodCall.setMethod( new ConstantExpression('_in_' + methodName) )
            fixMethodCall(methodCall)

        }

        /**
         * Transform a map entry `emit: something` into `emit: 'something'
         * (ie. as a constant) in a map expression passed as argument to
         * a method call. This allow the syntax
         *
         *   output:
         *   path 'foo', emit: bar
         *
         * @param call
         */
        protected void fixOutEmitOption(MethodCallExpression call) {
            List<Expression> args = isTupleX(call.arguments)?.expressions
            if( !args ) return
            if( args.size()<2 && (args.size()!=1 || call.methodAsString!='_out_stdout')) return
            MapExpression map = isMapX(args[0])
            if( !map ) return
            for( int i=0; i<map.mapEntryExpressions.size(); i++ ) {
                final entry = map.mapEntryExpressions[i]
                final key = isConstX(entry.keyExpression)
                final val = isVariableX(entry.valueExpression)
                if( key?.text == 'emit' && val ) {
                    map.mapEntryExpressions[i] = new MapEntryExpression(key, constX(val.text))
                }
            }
        }

        private static final VALID_OUTPUT_METHODS = ['val','env','file','path','stdout','tuple']

        protected void convertOutputMethod( Expression expression ) {
            // don't throw error if not method because it could be an implicit script statement
            if( expression !instanceof MethodCallExpression )
                return

            def methodCall = expression as MethodCallExpression
            def methodName = methodCall.getMethodAsString()
            log.trace "convert > output method: $methodName"

            def caller = methodCall.objectExpression
            if( caller !instanceof VariableExpression || caller.getText() != 'this' ) {
                syntaxError(expression, "Invalid process output statement, possible syntax error")
                return
            }

            if( methodName !in VALID_OUTPUT_METHODS ) {
                syntaxError(expression, "Invalid process output method '${methodName}'")
                return
            }

            methodCall.setMethod( new ConstantExpression('_out_' + methodName) )
            fixMethodCall(methodCall)
            fixOutEmitOption(methodCall)

        }

        private boolean withinTupleMethod

        private boolean withinEachMethod

        /**
         * This method converts the a method call argument from a Variable to a Constant value
         * so that it is possible to reference variable that not yet exist
         *
         * @param methodCall The method object for which it is required to change args definition
         * @param flagVariable Whenever append a flag specified if the variable replacement has been applied
         * @param index The index of the argument to modify
         * @return
         */
        protected void fixMethodCall( MethodCallExpression methodCall ) {
            final name = methodCall.methodAsString

            withinTupleMethod = name == '_in_tuple' || name == '_out_tuple'
            withinEachMethod = name == '_in_each'

            try {
                if( isOutputWithPropertyExpression(methodCall) ) {
                    // transform an output value declaration such
                    //   output: val( obj.foo )
                    // to
                    //   output: val({ obj.foo })
                    wrapPropertyToClosure((ArgumentListExpression)methodCall.getArguments())
                }
                else
                    varToConstX(methodCall.getArguments())

            } finally {
                withinTupleMethod = false
                withinEachMethod = false
            }
        }

        static final private List<String> OUT_PROPERTY_VALID_TYPES = ['_out_val', '_out_env', '_out_file', '_out_path']

        protected boolean isOutputWithPropertyExpression(MethodCallExpression methodCall) {
            if( methodCall.methodAsString !in OUT_PROPERTY_VALID_TYPES  )
                return false
            if( methodCall.getArguments() instanceof ArgumentListExpression ) {
                def args = (ArgumentListExpression)methodCall.getArguments()
                if( args.size()==0 || args.size()>2 )
                    return false

                return args.last() instanceof PropertyExpression
            }

            return false
        }

        protected void wrapPropertyToClosure(ArgumentListExpression expr) {
            final args = expr as ArgumentListExpression
            final property = (PropertyExpression) args.last()
            final closure = wrapPropertyToClosure(property)
            args.getExpressions().set(args.size()-1, closure)
        }

        protected ClosureExpression wrapPropertyToClosure(PropertyExpression property)  {
            def block = new BlockStatement()
            block.addStatement( new ExpressionStatement(property) )

            def closure = new ClosureExpression( Parameter.EMPTY_ARRAY, block )
            closure.variableScope = new VariableScope(block.variableScope)

            return closure
        }


        protected Expression varToStrX( Expression expr ) {
            if( expr instanceof VariableExpression ) {
                def name = ((VariableExpression) expr).getName()
                return createX( TokenVar, new ConstantExpression(name) )
            }
            else if( expr instanceof PropertyExpression ) {
                // transform an output declaration such
                // output: tuple val( obj.foo )
                //  to
                // output: tuple val({ obj.foo })
                return wrapPropertyToClosure(expr)
            }

            if( expr instanceof TupleExpression )  {
                def i = 0
                def list = expr.getExpressions()
                for( Expression item : list ) {
                    list[i++] = varToStrX(item)
                }

                return expr
            }

            return expr
        }

        protected Expression varToConstX( Expression expr ) {

            if( expr instanceof VariableExpression ) {
                // when it is a variable expression, replace it with a constant representing
                // the variable name
                def name = ((VariableExpression) expr).getName()

                /*
                 * the 'stdin' is used as placeholder for the standard input in the tuple definition. For example:
                 *
                 * tuple( stdin, .. )
                 */
                if( name == 'stdin' && withinTupleMethod )
                    return createX( TokenStdinCall )

                /*
                 * tuple( stdout, .. )
                 */
                else if ( name == 'stdout' && withinTupleMethod )
                    return createX( TokenStdoutCall )

                else
                    return createX( TokenVar, new ConstantExpression(name) )
            }

            if( expr instanceof MethodCallExpression ) {
                def methodCall = expr as MethodCallExpression

                /*
                 * replace 'file' method call in the tuple definition, for example:
                 *
                 * tuple( file(fasta:'*.fa'), .. )
                 */
                if( methodCall.methodAsString == 'file' && (withinTupleMethod || withinEachMethod) ) {
                    def args = (TupleExpression) varToConstX(methodCall.arguments)
                    return createX( TokenFileCall, args )
                }
                else if( methodCall.methodAsString == 'path' && (withinTupleMethod || withinEachMethod) ) {
                    def args = (TupleExpression) varToConstX(methodCall.arguments)
                    return createX( TokenPathCall, args )
                }

                /*
                 * tuple( env(VAR_NAME) )
                 */
                if( methodCall.methodAsString == 'env' && withinTupleMethod ) {
                    def args = (TupleExpression) varToStrX(methodCall.arguments)
                    return createX( TokenEnvCall, args )
                }

                /*
                 * tuple( val(x), .. )
                 */
                if( methodCall.methodAsString == 'val' && withinTupleMethod ) {
                    def args = (TupleExpression) varToStrX(methodCall.arguments)
                    return createX( TokenValCall, args )
                }

            }

            // -- TupleExpression or ArgumentListExpression
            if( expr instanceof TupleExpression )  {
                def i = 0
                def list = expr.getExpressions()
                for( Expression item : list )  {
                    list[i++] = varToConstX(item)
                }
                return expr
            }

            return expr
        }

        /**
         * Wrap a generic expression with in a closure expression
         *
         * @param block The block to which the resulting closure has to be appended
         * @param expr The expression to the wrapped in a closure
         * @param len
         * @return A tuple in which:
         *      <li>1st item: {@code true} if successful or {@code false} otherwise
         *      <li>2nd item: on error condition the line containing the error in the source script, zero otherwise
         *      <li>3rd item: on error condition the column containing the error in the source script, zero otherwise
         *
         */
        protected boolean wrapExpressionWithClosure( BlockStatement block, Expression expr, int len, CharSequence source, SourceUnit unit ) {
            if( expr instanceof GStringExpression || expr instanceof ConstantExpression ) {
                // remove the last expression
                block.statements.remove(len-1)

                // and replace it by a wrapping closure
                def closureExp = new ClosureExpression( Parameter.EMPTY_ARRAY, new ExpressionStatement(expr) )
                closureExp.variableScope = new VariableScope(block.variableScope)

                // append to the list of statement
                //def wrap = newObj(BodyDef, closureExp, new ConstantExpression(source.toString()), ConstantExpression.TRUE)
                def wrap = makeScriptWrapper(closureExp, source, 'script', unit )
                block.statements.add( new ExpressionStatement(wrap) )

                return true
            }
            else if( expr instanceof ClosureExpression ) {
                // do not touch it
                return true
            }
            else {
                log.trace "Invalid process result expression: ${expr} -- Only constant or string expression can be used"
            }

            return false
        }

        protected boolean isIllegalName(String name, ASTNode node) {
            if( name in RESERVED_NAMES ) {
                unit.addError( new SyntaxException("Identifier `$name` is reserved for internal use", node.lineNumber, node.columnNumber+8) )
                return true
            }
            if( name in workflowNames || name in processNames ) {
                unit.addError( new SyntaxException("Identifier `$name` is already used by another definition", node.lineNumber, node.columnNumber+8) )
                return true
            }
            if( name.contains(SCOPE_SEP) ) {
                def offset =  8+2+ name.indexOf(SCOPE_SEP)
                unit.addError( new SyntaxException("Process and workflow names cannot contain colon character", node.lineNumber, node.columnNumber+offset) )
                return true
            }
            return false
        }

        /**
         * This method handle the process definition, so that it transform the user entered syntax
         *    process myName { code .. }
         *
         * into
         *    process ( 'myName' )  { }
         *
         * @param methodCall
         * @param unit
         */
        protected void convertProcessDef( MethodCallExpression methodCall, SourceUnit unit ) {
            log.trace "Converts 'process' ${methodCall.arguments}"

            assert methodCall.arguments instanceof ArgumentListExpression
            def list = (methodCall.arguments as ArgumentListExpression).getExpressions()

            // extract the first argument which has to be a method-call expression
            // the name of this method represent the *process* name
            if( list.size() != 1 || !list[0].class.isAssignableFrom(MethodCallExpression) ) {
                log.debug "Missing name in process definition at line: ${methodCall.lineNumber}"
                unit.addError( new SyntaxException("Process definition syntax error -- A string identifier must be provided after the `process` keyword", methodCall.lineNumber, methodCall.columnNumber+7))
                return
            }

            def nested = list[0] as MethodCallExpression
            def name = nested.getMethodAsString()
            // check the process name is not defined yet
            if( isIllegalName(name, methodCall) ) {
                return
            }
            processNames.add(name)

            // make sure there is a single nested argument, which is a closure
            def args = nested.getArguments() as ArgumentListExpression
            log.trace "Process name: $name with args: $args"

            if( args.size() != 1 || args[0] !instanceof ClosureExpression ) {
                syntaxError(methodCall, "Invalid process definition")
                return
            }

            // wrap the process name in a string literal and add to the arguments
            list = args.getExpressions()
            list.add(0, new ConstantExpression(name))

            // set the new list as the new arguments
            methodCall.setArguments( args )

            // now continue as before !
            convertProcessBlock(methodCall, unit)
        }

        /**
         * Fetch all the variable references in a closure expression.
         *
         * @param closure
         * @param unit
         * @return The set of variable names referenced in the script. NOTE: it includes properties in the form {@code object.propertyName}
         */
        protected Set<TokenValRef> fetchVariables( ClosureExpression closure, SourceUnit unit ) {
            def visitor = new VariableVisitor(unit)
            visitor.visitClosureExpression(closure)
            return visitor.allVariables
        }

    }

}
