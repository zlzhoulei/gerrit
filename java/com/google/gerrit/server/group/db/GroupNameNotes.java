// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.server.group.db;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.git.VersionedMetaData;
import com.google.gwtorm.server.OrmDuplicateKeyException;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

// TODO(aliceks): Add Javadoc descriptions.
public class GroupNameNotes extends VersionedMetaData {
  private static final String SECTION_NAME = "group";
  private static final String UUID_PARAM = "uuid";
  private static final String NAME_PARAM = "name";

  private final AccountGroup.UUID groupUuid;
  private final Optional<AccountGroup.NameKey> oldGroupName;
  private final Optional<AccountGroup.NameKey> newGroupName;

  private boolean nameConflicting;

  private GroupNameNotes(
      AccountGroup.UUID groupUuid,
      @Nullable AccountGroup.NameKey oldGroupName,
      @Nullable AccountGroup.NameKey newGroupName) {
    this.groupUuid = checkNotNull(groupUuid);

    if (Objects.equals(oldGroupName, newGroupName)) {
      this.oldGroupName = Optional.empty();
      this.newGroupName = Optional.empty();
    } else {
      this.oldGroupName = Optional.ofNullable(oldGroupName);
      this.newGroupName = Optional.ofNullable(newGroupName);
    }
  }

  public static GroupNameNotes loadForRename(
      Repository repository,
      AccountGroup.UUID groupUuid,
      AccountGroup.NameKey oldName,
      AccountGroup.NameKey newName)
      throws IOException, ConfigInvalidException, OrmDuplicateKeyException {
    checkNotNull(oldName);
    checkNotNull(newName);

    GroupNameNotes groupNameNotes = new GroupNameNotes(groupUuid, oldName, newName);
    groupNameNotes.load(repository);
    groupNameNotes.ensureNewNameIsNotUsed();
    return groupNameNotes;
  }

  public static GroupNameNotes loadForNewGroup(
      Repository repository, AccountGroup.UUID groupUuid, AccountGroup.NameKey groupName)
      throws IOException, ConfigInvalidException, OrmDuplicateKeyException {
    checkNotNull(groupName);

    GroupNameNotes groupNameNotes = new GroupNameNotes(groupUuid, null, groupName);
    groupNameNotes.load(repository);
    groupNameNotes.ensureNewNameIsNotUsed();
    return groupNameNotes;
  }

  public static ImmutableSet<GroupReference> loadAllGroupReferences(Repository repository)
      throws IOException, ConfigInvalidException {
    Ref ref = repository.exactRef(RefNames.REFS_GROUPNAMES);
    if (ref == null) {
      return ImmutableSet.of();
    }
    try (RevWalk revWalk = new RevWalk(repository);
        ObjectReader reader = revWalk.getObjectReader()) {
      RevCommit notesCommit = revWalk.parseCommit(ref.getObjectId());
      NoteMap noteMap = NoteMap.read(reader, notesCommit);
      ImmutableSet.Builder<GroupReference> groupReferences = ImmutableSet.builder();
      for (Note note : noteMap) {
        GroupReference groupReference = getGroupReference(reader, note.getData());
        groupReferences.add(groupReference);
      }
      return groupReferences.build();
    }
  }

  @Override
  protected String getRefName() {
    return RefNames.REFS_GROUPNAMES;
  }

  @Override
  protected void onLoad() throws IOException, ConfigInvalidException {
    nameConflicting = false;

    if (revision != null) {
      NoteMap noteMap = NoteMap.read(reader, revision);
      if (newGroupName.isPresent()) {
        ObjectId newNameId = getNoteKey(newGroupName.get());
        nameConflicting = noteMap.contains(newNameId);
      }
      ensureOldNameIsPresent(noteMap);
    }
  }

  private void ensureOldNameIsPresent(NoteMap noteMap) throws IOException, ConfigInvalidException {
    if (oldGroupName.isPresent()) {
      AccountGroup.NameKey oldName = oldGroupName.get();
      ObjectId noteKey = getNoteKey(oldName);
      ObjectId noteDataBlobId = noteMap.get(noteKey);
      if (noteDataBlobId == null) {
        throw new ConfigInvalidException(
            String.format("Group name '%s' doesn't exist in the list of all names", oldName));
      }
      GroupReference group = getGroupReference(reader, noteDataBlobId);
      AccountGroup.UUID foundUuid = group.getUUID();
      if (!Objects.equals(groupUuid, foundUuid)) {
        throw new ConfigInvalidException(
            String.format(
                "Name '%s' points to UUID '%s' and not to '%s'", oldName, foundUuid, groupUuid));
      }
    }
  }

  private void ensureNewNameIsNotUsed() throws OrmDuplicateKeyException {
    if (newGroupName.isPresent() && nameConflicting) {
      throw new OrmDuplicateKeyException(
          String.format("Name '%s' is already used", newGroupName.get().get()));
    }
  }

  @Override
  protected boolean onSave(CommitBuilder commit) throws IOException, ConfigInvalidException {
    if (!oldGroupName.isPresent() && !newGroupName.isPresent()) {
      return false;
    }

    NoteMap noteMap = revision == null ? NoteMap.newEmptyMap() : NoteMap.read(reader, revision);
    if (oldGroupName.isPresent()) {
      removeNote(noteMap, oldGroupName.get(), inserter);
    }

    if (newGroupName.isPresent()) {
      addNote(noteMap, newGroupName.get(), groupUuid, inserter);
    }

    commit.setTreeId(noteMap.writeTree(inserter));

    return true;
  }

  private static void removeNote(
      NoteMap noteMap, AccountGroup.NameKey groupName, ObjectInserter inserter) throws IOException {
    ObjectId noteKey = getNoteKey(groupName);
    noteMap.set(noteKey, null, inserter);
  }

  private static void addNote(
      NoteMap noteMap,
      AccountGroup.NameKey groupName,
      AccountGroup.UUID groupUuid,
      ObjectInserter inserter)
      throws IOException {
    ObjectId noteKey = getNoteKey(groupName);
    noteMap.set(noteKey, getAsNoteData(groupUuid, groupName), inserter);
  }

  // Use the same approach as ExternalId.Key.sha1().
  @SuppressWarnings("deprecation")
  private static ObjectId getNoteKey(AccountGroup.NameKey groupName) {
    return ObjectId.fromRaw(Hashing.sha1().hashString(groupName.get(), UTF_8).asBytes());
  }

  private static String getAsNoteData(AccountGroup.UUID uuid, AccountGroup.NameKey groupName) {
    Config config = new Config();
    config.setString(SECTION_NAME, null, UUID_PARAM, uuid.get());
    config.setString(SECTION_NAME, null, NAME_PARAM, groupName.get());
    return config.toText();
  }

  private static GroupReference getGroupReference(ObjectReader reader, ObjectId noteDataBlobId)
      throws IOException, ConfigInvalidException {
    byte[] noteData = reader.open(noteDataBlobId, OBJ_BLOB).getCachedBytes();
    return getFromNoteData(noteData);
  }

  private static GroupReference getFromNoteData(byte[] noteData) throws ConfigInvalidException {
    Config config = new Config();
    config.fromText(new String(noteData, UTF_8));

    String uuid = config.getString(SECTION_NAME, null, UUID_PARAM);
    String name = config.getString(SECTION_NAME, null, NAME_PARAM);
    if (uuid == null || name == null) {
      throw new ConfigInvalidException(
          String.format("UUID '%s' and name '%s' must be defined", uuid, name));
    }

    return new GroupReference(new AccountGroup.UUID(uuid), name);
  }
}
