/*
 * Copyright 2013-2024, Seqera Labs
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

package nextflow.config.v2

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.config.ConfigClosurePlaceholder
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
/**
 * AST transformation to render closure source text
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.CONVERSION)
class ClosureToStringXformImpl implements ASTTransformation {

    @Override
    void visit(ASTNode[] astNodes, SourceUnit unit) {
        final clazz = (ClassNode)astNodes[1]
        new ClosureToStringVisitor(unit: unit).visitClass(clazz)
    }

    @CompileStatic
    static class ClosureToStringVisitor extends ClassCodeVisitorSupport {

        protected SourceUnit unit

        @Override
        protected SourceUnit getSourceUnit() { unit }

        @Override
        void visitMethodCallExpression(MethodCallExpression methodCall) {
            final name = methodCall.methodAsString
            if( name == 'block' )
                super.visitMethodCallExpression(methodCall)
            if( name != 'assign' )
                return

            final arguments = (ArgumentListExpression)methodCall.arguments
            if( arguments.size() != 2 )
                return

            final arg = arguments.last()
            if( arg instanceof MapExpression ) {
                for( final entry : arg.mapEntryExpressions )
                    if( entry.valueExpression instanceof ClosureExpression )
                        entry.valueExpression = closureToString(entry.valueExpression)
            }
            if( arg instanceof ClosureExpression ) {
                final placeholder = closureToString(arg)
                methodCall.arguments = new ArgumentListExpression(arguments[0], placeholder)
            }
        }

        protected Expression closureToString(Expression closure) {
            final buffer = new StringBuilder()
            readSource(closure, buffer)
            final str = new ConstantExpression(buffer.toString())

            final type = new ClassNode(ConfigClosurePlaceholder)
            final args = new ArgumentListExpression(str)
            return new ConstructorCallExpression(type, args)
        }

        protected void readSource(Expression expr, StringBuilder buffer) {
            final colBegin = Math.max(expr.getColumnNumber()-1, 0)
            final colEnd = Math.max(expr.getLastColumnNumber()-1, 0)
            final lineFirst = expr.getLineNumber()
            final lineLast = expr.getLastLineNumber()

            for( int i=lineFirst; i<=lineLast; i++ ) {
                def line = unit.source.getLine(i, null)
                if( i==lineFirst ) {
                    def str = i==lineLast ? line.substring(colBegin,colEnd) : line.substring(colBegin)
                    buffer.append(str)
                }
                else {
                    def str = i==lineLast ? line.substring(0, colEnd) : line
                    buffer.append('\n')
                    buffer.append(str)
                }
            }
        }

    }

}