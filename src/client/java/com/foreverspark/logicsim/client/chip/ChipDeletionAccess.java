package com.foreverspark.logicsim.client.chip;

import java.io.IOException;
import java.util.List;

/** Dependency-aware deletion for user-created reusable chips. */
public interface ChipDeletionAccess {
    List<String> logic$dependentsOf(String chipName) throws IOException;
    void logic$deleteChip(String chipName) throws IOException;
}
