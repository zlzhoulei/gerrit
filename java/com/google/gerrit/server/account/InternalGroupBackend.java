// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.account;

import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.common.data.GroupDescription;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.group.InternalGroupDescription;
import com.google.gerrit.server.group.db.Groups;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Collection;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;

/** Implementation of GroupBackend for the internal group system. */
@Singleton
public class InternalGroupBackend implements GroupBackend {
  private final GroupControl.Factory groupControlFactory;
  private final GroupCache groupCache;
  private final Groups groups;
  private final SchemaFactory<ReviewDb> schema;
  private final IncludingGroupMembership.Factory groupMembershipFactory;

  @Inject
  InternalGroupBackend(
      GroupControl.Factory groupControlFactory,
      GroupCache groupCache,
      Groups groups,
      SchemaFactory<ReviewDb> schema,
      IncludingGroupMembership.Factory groupMembershipFactory) {
    this.groupControlFactory = groupControlFactory;
    this.groupCache = groupCache;
    this.groups = groups;
    this.schema = schema;
    this.groupMembershipFactory = groupMembershipFactory;
  }

  @Override
  public boolean handles(AccountGroup.UUID uuid) {
    // See AccountGroup.isInternalGroup
    return ObjectId.isId(uuid.get()); // [0-9a-f]{40};
  }

  @Override
  public GroupDescription.Internal get(AccountGroup.UUID uuid) {
    if (!handles(uuid)) {
      return null;
    }

    return groupCache.get(uuid).map(InternalGroupDescription::new).orElse(null);
  }

  @Override
  public Collection<GroupReference> suggest(String name, ProjectState project) {
    try (ReviewDb db = schema.open()) {
      return groups
          .getAllGroupReferences(db)
          .filter(group -> startsWithIgnoreCase(group, name))
          .filter(this::isVisible)
          .collect(toList());
    } catch (OrmException | IOException | ConfigInvalidException e) {
      return ImmutableList.of();
    }
  }

  private static boolean startsWithIgnoreCase(GroupReference group, String name) {
    return group.getName().regionMatches(true, 0, name, 0, name.length());
  }

  private boolean isVisible(GroupReference groupReference) {
    return groupCache
        .get(groupReference.getUUID())
        .map(InternalGroupDescription::new)
        .map(groupControlFactory::controlFor)
        .filter(GroupControl::isVisible)
        .isPresent();
  }

  @Override
  public GroupMembership membershipsOf(IdentifiedUser user) {
    return groupMembershipFactory.create(user);
  }

  @Override
  public boolean isVisibleToAll(AccountGroup.UUID uuid) {
    GroupDescription.Internal g = get(uuid);
    return g != null && g.isVisibleToAll();
  }
}
