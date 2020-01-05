/*
 * Copyright 2016-2020 Cloudera, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hortonworks.registries.schemaregistry.authorizer.agent;

import com.hortonworks.registries.schemaregistry.authorizer.AuthorizerFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

public class AuthorizationAgentFactory {
    public static AuthorizationAgent getAuthorizationAgent(Map<String, Object> props) {
        if(props == null
                || props.size() == 0
                || !props.containsKey("authorizerAgentClassName")
                || props.get("authorizerAgentClassName")
                .equals(DummyAuthorizationAgent.class.getCanonicalName())) {
            return new DummyAuthorizationAgent();
        }

        String cName = (String) props.get("authorizerAgentClassName");
        if(cName.equals(DefaultAuthorizationAgent.class.getCanonicalName())) {
            DefaultAuthorizationAgent.INSTANCE.init(AuthorizerFactory.getAuthorizer(props));
            return DefaultAuthorizationAgent.INSTANCE;
        }

        // In case if in future we plan to add any other custom AuthorizationAgents
        try {
            Class<AuthorizationAgent> cl = (Class<AuthorizationAgent>) Class.forName(cName);
            Constructor<AuthorizationAgent> constr = cl.getConstructor();
            AuthorizationAgent res = constr.newInstance();
            res.init(AuthorizerFactory.getAuthorizer(props));

            return res;
        } catch (ClassNotFoundException | InstantiationException
                | IllegalAccessException | NoSuchMethodException
                | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

}
