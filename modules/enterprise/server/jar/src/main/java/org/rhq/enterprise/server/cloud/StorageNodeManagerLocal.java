/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.server.cloud;

import java.util.List;

import javax.ejb.Local;

import org.rhq.core.domain.auth.Subject;
import org.rhq.core.domain.cloud.StorageNode;
import org.rhq.core.domain.cloud.StorageNodeLoadComposite;
import org.rhq.core.domain.criteria.StorageNodeCriteria;
import org.rhq.core.domain.resource.Resource;
import org.rhq.core.domain.util.PageList;

@Local
public interface StorageNodeManagerLocal {

    List<StorageNode> scanForStorageNodes();

    List<StorageNode> getStorageNodes();

    void linkResource(Resource resource);

    /**
     * <p>Returns the summary of load of the storage node.</p>
     *
     * <p>the subject needs to have <code>MANAGE_SETTINGS</code> permissions.</p>
     *
     * @param subject   user that must have proper permissions
     * @param node      storage node entity (it can be a new object, but the id should be set properly)
     * @param beginTime the start time
     * @param endTime   the end time
     * @return instance of {@link StorageNodeLoadComposite} with the aggregate measurement data of selected metrics
     */
    StorageNodeLoadComposite getLoad(Subject subject, StorageNode node, long beginTime, long endTime);

    /**
     * Fetches the list of StorageNode entities based on provided criteria.
     *
     * the subject needs to have MANAGE_SETTINGS permissions.
     *
     * @param subject caller
     * @param criteria the criteria
     * @return list of nodes
     */
    PageList<StorageNode> findStorageNodesByCriteria(Subject subject, StorageNodeCriteria criteria);

}