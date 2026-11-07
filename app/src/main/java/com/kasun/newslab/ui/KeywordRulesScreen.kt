package com.kasun.newslab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.ui.theme.nd

/** Manage keyword alert rules. */
@Composable
fun KeywordRulesScreen(vm: MainViewModel, onBack: () -> Unit, listState: LazyListState) {
    val rules by vm.rules.collectAsState()
    // Saveable ids (not entity objects) so open dialogs survive rotation.
    var showAddRule by rememberSaveable { mutableStateOf(false) }
    var editRuleId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDeleteRuleId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = nd.ink)
            }
            Text("Keyword Rules", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = nd.ink)
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rules, key = { "rule-${it.id}" }) { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            // A live rule wears the amber tag; a paused one is outlined and
                            // faint — amber withdrawn entirely, since the rule is off.
                            if (rule.enabled) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(nd.radiusTag))
                                        .background(nd.tagBg)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        rule.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = nd.tagText,
                                    )
                                }
                            } else {
                                Box(
                                    Modifier
                                        .border(1.dp, nd.border)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        rule.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = nd.faint,
                                    )
                                }
                            }
                        }
                        if (rule.query != rule.label) {
                            Spacer(Modifier.height(3.dp))
                            Text("matches: ${rule.query}", fontSize = 12.sp, color = nd.faint)
                        }
                    }
                    IconButton(onClick = { editRuleId = rule.id }) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Edit rule",
                            modifier = Modifier.size(19.dp),
                            tint = nd.faint,
                        )
                    }
                    NdSwitch(checked = rule.enabled, onCheckedChange = { vm.toggleRule(rule) })
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { confirmDeleteRuleId = rule.id }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete rule",
                            modifier = Modifier.size(20.dp),
                            tint = nd.faint,
                        )
                    }
                }
            }
            item { AddRowButton("Add keyword rule") { showAddRule = true } }
            item {
                Text(
                    "A rule fires when all of its words appear in an article's title or summary, " +
                        "in any order. Wrap words in \"quotes\" to require an exact phrase. " +
                        "Matching is case-insensitive. Matches are pinned to the top of your inbox " +
                        "and shown in the Alerts tab.",
                    Modifier.padding(18.dp),
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    if (showAddRule) {
        RuleDialog(
            onDismiss = { showAddRule = false },
            onConfirm = { label, query ->
                vm.addRule(label, query)
                showAddRule = false
            },
        )
    }
    rules.firstOrNull { it.id == editRuleId }?.let { rule ->
        RuleDialog(
            title = "Edit keyword rule",
            initialLabel = rule.label,
            initialQuery = rule.query,
            onDismiss = { editRuleId = null },
            onConfirm = { label, query ->
                vm.updateRule(rule, label, query)
                editRuleId = null
            },
        )
    }
    rules.firstOrNull { it.id == confirmDeleteRuleId }?.let { rule ->
        ConfirmDialog(
            title = "Delete \"${rule.label}\"?",
            body = "Matched articles lose their alert badge, and old matches become " +
                "eligible for the regular 14-day cleanup.",
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteRuleId = null },
            onConfirm = {
                vm.deleteRule(rule)
                confirmDeleteRuleId = null
            },
        )
    }
}
