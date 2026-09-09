package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.mezze.datastructures.list.MezzeList;

/**
 * Descriptor node for List-sourced iteration.
 *
 * Converts the linked list to an array at sink time and iterates the
 * array — same strategy as the FLP RawList source. This avoids O(n)
 * linked-list traversal per element.
 */
public final class ListIterNode extends IterNode {

    @CompilationFinal
    public final MezzeList list;

    public ListIterNode(MezzeList list) {
        this.list = list;
    }
}
