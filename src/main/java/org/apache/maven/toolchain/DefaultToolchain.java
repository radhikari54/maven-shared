/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.maven.toolchain;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author mkleint
 */
public abstract class DefaultToolchain
    implements Toolchain, ToolchainPrivate
{

    private String type;

    private Map provides = new HashMap /*<String,RequirementMatcher>*/ (  );

    public static final String KEY_TYPE = "type"; //NOI18N

    protected DefaultToolchain( )
    {
    }

    protected DefaultToolchain( String type )
    {
        this.type = type;
    }

    public final String getType( )
    {
        return type;
    }

    public final String getStorageKey( )
    {
        return "toolchain-" + type; //NOI18N
    }

    public Map getData( )
    {
        Map data = new HashMap( 2 );
        data.put( KEY_TYPE, type );

        return data;
    }

    public void setData( Map data )
    {
        type = (String) data.get( KEY_TYPE );
    }

    public final void addProvideToken( String type,
                                       RequirementMatcher matcher )
    {
        provides.put( type, matcher );
    }

    public Map getRequirementMatchers( )
    {
        return new HashMap( provides );
    }
}