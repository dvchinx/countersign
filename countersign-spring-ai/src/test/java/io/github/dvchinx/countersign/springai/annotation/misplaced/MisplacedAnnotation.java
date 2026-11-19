package io.github.dvchinx.countersign.springai.annotation.misplaced;

import io.github.dvchinx.countersign.springai.annotation.RequiresApproval;

/** Test fixture: the annotation on a method that is not a tool, which would silently do nothing. */
public class MisplacedAnnotation {

    @RequiresApproval(reason = "Moves real money")
    public String transfer() {
        return "nothing guards me";
    }
}
