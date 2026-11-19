package io.github.dvchinx.countersign.autoconfigure.misplaced;

import io.github.dvchinx.countersign.springai.annotation.RequiresApproval;

/** Test fixture: an annotation that would do nothing, which must stop the application. */
public class NotATool {

    @RequiresApproval
    public void transfer() {}
}
