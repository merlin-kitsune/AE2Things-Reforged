package io.github.projectet.ae2things.util;

public interface Constants {

    /**
     * NBT key of the disk id on the disk item, and of the disk id in the saved disk list. Both have to keep this value:
     * worlds that were created by older versions of this mod are read with it.
     */
    String DISKUUID = "diskuuid";
    String MANAGER_NAME = "disk_manager";
    String DISKDATA = "diskdata";
    String DISKLIST = "disklist";
    /** NBT key of the fuzzy mode on the disk item. */
    String FUZZY_MODE = "FuzzyMode";

}
