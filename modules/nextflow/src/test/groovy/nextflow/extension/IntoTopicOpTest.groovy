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
 *
 */

package nextflow.extension

import nextflow.Channel
import test.Dsl2Spec
import test.MockScriptRunner

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class IntoTopicOpTest extends Dsl2Spec {

    def 'should send a channel into a topic' () {
        given:
        def SCRIPT = '''
        nextflow.preview.topic = true
        Channel.of(1,2,3) | topic('foo')
        Channel.value(4) | topic('foo')
        Channel.topic('foo').collect()
        '''

        when:
        def runner = new MockScriptRunner()
        def result = runner.setScript(SCRIPT).execute().getVal()
        then:
        1 in result
        2 in result
        3 in result
        4 in result
    }

}