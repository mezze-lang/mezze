@file:Suppress("unused")
package com.mezze.language.codebase

/**
 * Re-exports from the store module.
 *
 * The canonical definitions live in com.mezze.language.store.
 * These type aliases preserve backward compatibility so existing
 * imports (import com.mezze.language.codebase.MezzeStore) keep working.
 *
 * New code should import from com.mezze.language.store directly.
 */

typealias MezzeStore = com.mezze.language.store.MezzeStore
typealias BlobKind = com.mezze.language.store.BlobKind
typealias Visibility = com.mezze.language.store.Visibility
typealias EdgeKind = com.mezze.language.store.EdgeKind
typealias DeclKind = com.mezze.language.store.DeclKind
typealias BlobRow = com.mezze.language.store.BlobRow
typealias NameRow = com.mezze.language.store.NameRow
typealias ModuleRow = com.mezze.language.store.ModuleRow
typealias EdgeRow = com.mezze.language.store.EdgeRow
typealias AbilityImplRow = com.mezze.language.store.AbilityImplRow
typealias EffectPerformerRow = com.mezze.language.store.EffectPerformerRow
typealias UsageRow = com.mezze.language.store.UsageRow
typealias DeclRow = com.mezze.language.store.DeclRow
typealias ImportRow = com.mezze.language.store.ImportRow
typealias AppRow = com.mezze.language.store.AppRow
typealias MonoSpecializationRow = com.mezze.language.store.MonoSpecializationRow
