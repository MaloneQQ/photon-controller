/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.apife.db.dao;

import com.vmware.photon.controller.api.UsageTag;
import com.vmware.photon.controller.apife.entities.PortGroupEntity;

import com.google.inject.Inject;
import org.hibernate.SessionFactory;

import java.util.List;

/**
 * Dao for PortGroup.
 */
public class PortGroupDao extends ExtendedAbstractDao<PortGroupEntity> {

  @Inject
  public PortGroupDao(SessionFactory sessionFactory) {
    super(sessionFactory);
  }

  public List<PortGroupEntity> listAllByUsage(UsageTag usageTag) {
    return list(namedQuery(getEntityClassName() + ".listAllByUsage")
        .setString("usageTag", "%" + usageTag + "%"));
  }
}