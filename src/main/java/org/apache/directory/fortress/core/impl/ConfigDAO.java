/*
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */
package org.apache.directory.fortress.core.impl;


import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.directory.api.ldap.model.constants.SchemaConstants;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.entry.DefaultModification;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Modification;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.api.ldap.model.exception.LdapEntryAlreadyExistsException;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.exception.LdapNoSuchObjectException;
import org.apache.directory.fortress.core.CreateException;
import org.apache.directory.fortress.core.FinderException;
import org.apache.directory.fortress.core.GlobalErrIds;
import org.apache.directory.fortress.core.GlobalIds;
import org.apache.directory.fortress.core.RemoveException;
import org.apache.directory.fortress.core.UpdateException;
import org.apache.directory.fortress.core.ldap.LdapDataProvider;
import org.apache.directory.fortress.core.util.PropUtil;
import org.apache.directory.fortress.core.util.Config;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class provides data access for the standard ldap object device that has been extended to support name/value pairs.
 * Fortress uses this data structure to store its remote cfg parameters.
 * <p>
 * The Fortress Config node is a combination of:
 * <p>
 * 'device' Structural Object Class is used to store basic attributes like cn which will be used for config node name.
 * <ul>
 * <li>  ------------------------------------------
 * <li> <code>objectclass ( 2.5.6.14 NAME 'device'</code>
 * <li> <code>DESC 'RFC2256: a device''</code>
 * <li> <code>SUP top STRUCTURAL</code>
 * <li> <code>MUST cn</code>
 * <li> <code>MAY ( serialNumber $ seeAlso $ owner $ ou $ o $ l $ description ) )</code>
 * <li>  ------------------------------------------
 * </ul>
 * <p>
 * 'ftProperties' AUXILIARY Object Class is used to store name/value pairs on target node.<br>
 * <code>This aux object class can be used to store custom attributes.</code><br>
 * <code>The properties collections consist of name/value pairs and are not constrainted by Fortress.</code><br>
 * <ul>
 * <li>  ------------------------------------------
 * <li> <code>objectclass ( 1.3.6.1.4.1.38088.3.2</code>
 * <li> <code>NAME 'ftProperties'</code>
 * <li> <code>DESC 'Fortress Properties AUX Object Class'</code>
 * <li> <code>AUXILIARY</code>
 * <li> <code>MAY ( ftProps ) ) </code>
 * <li>  ------------------------------------------
 * </ul>
 * <p>
 * This class is thread safe.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
final class ConfigDAO extends LdapDataProvider

{
    private static final String CLS_NM = ConfigDAO.class.getName();
    private static final Logger LOG = LoggerFactory.getLogger( CLS_NM );
    private String CONFIG_ROOT_DN;

    private final String CONFIG_OBJ_CLASS[] =
        {
            SchemaConstants.DEVICE_OC, GlobalIds.PROPS_AUX_OBJECT_CLASS_NAME
    };

    private final String[] CONFIG_ATRS =
        {
            SchemaConstants.CN_AT, GlobalIds.PROPS
    };


    /**
     * Package private default constructor.
     */
    ConfigDAO()
    {
    	super();
    	CONFIG_ROOT_DN = Config.getInstance().getProperty( GlobalIds.CONFIG_ROOT_PARAM );
    }


    /**
     * Create a new configuration node and load it with properties.
     * @param name of the new configuration node to be created in ldap.
     * @param props each name/value pair becomes an ftprop in this entry.
     * @return
     * @throws org.apache.directory.fortress.core.CreateException
     */
    Properties create( String name, Properties props )
        throws CreateException
    {
        LdapConnection ld = null;
        String dn = getDn( name );
        LOG.info( "create dn [{}]", dn );
        try
        {
            Entry myEntry = new DefaultEntry( dn );
            myEntry.add( SchemaConstants.OBJECT_CLASS_AT, CONFIG_OBJ_CLASS );
            ld = getAdminConnection();
            myEntry.add( SchemaConstants.CN_AT, name );
            loadProperties( props, myEntry, GlobalIds.PROPS );
            add( ld, myEntry );
        }
        catch ( LdapEntryAlreadyExistsException e )
        {
            String warning = "create config dn [" + dn + "] caught LdapEntryAlreadyExistsException="
                + e;
            throw new CreateException( GlobalErrIds.FT_CONFIG_ALREADY_EXISTS, warning, e );
        }
        catch ( LdapException e )
        {
            String error;
            error = "create config dn [" + dn + "] caught LDAPException=" + e;
            LOG.error( error, e );
            throw new CreateException( GlobalErrIds.FT_CONFIG_CREATE_FAILED, error, e );
        }
        finally
        {
            closeAdminConnection( ld );
        }
        return props;
    }


    /**
     * Update existing node with new properties.
     * @param name contains existing config node name
     * @param props each property name value will loaded into an attribute (ftprops) under the config node.
     * @return
     * @throws org.apache.directory.fortress.core.UpdateException
     */
    Properties update( String name, Properties props )
        throws UpdateException
    {
        LdapConnection ld = null;
        String dn = getDn( name );
        LOG.info( "update dn [{}]", dn );
        try
        {
            List<Modification> mods = new ArrayList<>();
            if ( PropUtil.isNotEmpty( props ) )
            {
                loadProperties( props, mods, GlobalIds.PROPS, false );
            }
            ld = getAdminConnection();
            if ( mods.size() > 0 )
            {
                ld = getAdminConnection();
                // TODO: change to use modify that leaves audit trail:
                modify( ld, dn, mods );
            }
        }
        catch ( LdapException e )
        {
            String error = "update dn [" + dn + "] caught LDAPException=" + e;
            throw new UpdateException( GlobalErrIds.FT_CONFIG_UPDATE_FAILED, error, e );
        }
        finally
        {
            closeAdminConnection( ld );
        }
        return props;
    }


    /**
     * This method will update a single property with a new value.
     *
     * @param name of the config node, mostly likely 'DEFAULT'.
     * @param key used for the property.
     * @param value this is old value to be replaced with newValue.
     * @param newValue new value for the property
     * @throws UpdateException in the event the attribute can't be replaced.
     * @throws FinderException in the event the config node and/or property key:value can't be located.
     */
    void updateProperty( String name, String key, String value, String newValue ) throws UpdateException, FinderException
    {
        LdapConnection ld = null;
        String dn = getDn( name );
        LOG.debug( "update dn [{}], key [{}], value [{}], newValue [{}]", dn, key, value, newValue );
        try
        {
            List<Modification> mods = new ArrayList<Modification>();
            mods.add( new DefaultModification( ModificationOperation.REMOVE_ATTRIBUTE, GlobalIds.PROPS, key + GlobalIds.PROP_SEP + value ) );
            mods.add( new DefaultModification( ModificationOperation.ADD_ATTRIBUTE, GlobalIds.PROPS, key + GlobalIds.PROP_SEP + newValue ) );
            ld = getAdminConnection();
            modify( ld, dn, mods );
        }
        catch ( LdapException e )
        {
            String error = "updateProperty dn [" + dn + "] caught LDAPException=" + e;
            throw new UpdateException( GlobalErrIds.FT_CONFIG_UPDATE_FAILED, error, e );
        }
        finally
        {
            closeAdminConnection( ld );
        }
    }


    /**
     * Remove the configuration node from the directory.
     * @param name of the node to be removed.
     * @throws org.apache.directory.fortress.core.RemoveException
     */
    void remove( String name )
        throws RemoveException
    {
        LdapConnection ld = null;
        String dn = getDn( name );
        LOG.info( "remove dn [{}]", dn );
        try
        {
            ld = getAdminConnection();
            delete( ld, dn );
        }
        catch ( LdapException e )
        {
            String error = "remove dn [" + dn + "] LDAPException=" + e;
            throw new RemoveException( GlobalErrIds.FT_CONFIG_DELETE_FAILED, error, e );
        }
        finally
        {
            closeAdminConnection( ld );
        }
    }


    /**
     * A set of properties from a configuration node.
     * @param name the name of config node.
     * @param props each name/value will be a ftprop attribute to remove.
     * @return
     * @throws org.apache.directory.fortress.core.UpdateException
     */
    Properties remove( String name, Properties props )
        throws UpdateException
    {
        LdapConnection ld = null;
        String dn = getDn( name );
        LOG.info( "remove props dn [{}]", dn );
        try
        {
            List<Modification> mods = new ArrayList<>();
            if ( PropUtil.isNotEmpty( props ) )
            {
                removeProperties( props, mods, GlobalIds.PROPS );
            }
            if ( mods.size() > 0 )
            {
                ld = getAdminConnection();
                modify( ld, dn, mods );
            }
        }
        catch ( LdapException e )
        {
            String error = "remove props dn [" + dn + "] caught LDAPException=" + e;
            throw new UpdateException( GlobalErrIds.FT_CONFIG_DELETE_PROPS_FAILED, error, e );
        }
        finally
        {
            closeAdminConnection( ld );
        }
        return props;
    }


    /**
     * @param name
     * @return
     * @throws org.apache.directory.fortress.core.FinderException
     */
    Properties getConfig( String name )
        throws FinderException
    {
        Properties props = null;
        LdapConnection ld = null;
        String dn = getDn( name );
        LOG.debug( "getConfig dn [{}]", dn );
        try
        {
            ld = getAdminConnection();
            Entry findEntry = read( ld, dn, CONFIG_ATRS );
            props = PropUtil.getProperties( getAttributes( findEntry, GlobalIds.PROPS ) );
        }
        catch ( LdapNoSuchObjectException e )
        {
            String warning = "getConfig COULD NOT FIND ENTRY for dn [" + dn + "]";
            throw new FinderException( GlobalErrIds.FT_CONFIG_NOT_FOUND, warning, e );
        }
        catch ( LdapException e )
        {
            String error = "getConfig dn [" + dn + "] caught LdapException=" + e;
            throw new FinderException( GlobalErrIds.FT_CONFIG_READ_FAILED, error, e );
        }
        finally
        {
            closeAdminConnection( ld );
        }
        return props;
    }


    /**
     *
     * @param name
     * @return
     */
    private String getDn( String name )
    {
        return SchemaConstants.CN_AT + "=" + name + "," + CONFIG_ROOT_DN;
    }
}