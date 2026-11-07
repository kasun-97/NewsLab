package com.kasun.newslab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.ui.theme.nd

/** Manage custom article categories. */
@Composable
fun CategoriesScreen(vm: MainViewModel, onBack: () -> Unit, listState: LazyListState) {
    val categories by vm.categories.collectAsState()
    // Saveable ids (not entity objects) so open dialogs survive rotation.
    var showAddCategory by rememberSaveable { mutableStateOf(false) }
    var editCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDeleteCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = nd.ink)
            }
            Text("Categories", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = nd.ink)
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(categories, key = { "cat-${it.id}" }) { category ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(category.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                        Text(
                            category.keywords.ifBlank { "No match terms — matches on the name only" },
                            fontSize = 12.sp,
                            color = nd.faint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { editCategoryId = category.id }) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Edit category",
                            modifier = Modifier.size(19.dp),
                            tint = nd.faint,
                        )
                    }
                    IconButton(onClick = { confirmDeleteCategoryId = category.id }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete category",
                            modifier = Modifier.size(20.dp),
                            tint = nd.faint,
                        )
                    }
                }
            }
            item { AddRowButton("Add category") { showAddCategory = true } }
            item {
                Text(
                    "Articles are auto-tagged with a category when their title or summary " +
                        "contains any of its match terms (publisher RSS tags count too). " +
                        "Categories appear as filter chips on the Inbox.",
                    Modifier.padding(18.dp),
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    if (showAddCategory) {
        CategoryDialog(
            title = "Add category",
            onDismiss = { showAddCategory = false },
            onConfirm = { name, keywords ->
                vm.addCategory(name, keywords)
                showAddCategory = false
            },
        )
    }
    categories.firstOrNull { it.id == editCategoryId }?.let { category ->
        CategoryDialog(
            title = "Edit category",
            initialName = category.name,
            initialKeywords = category.keywords,
            onDismiss = { editCategoryId = null },
            onConfirm = { name, keywords ->
                vm.updateCategory(category.copy(name = name, keywords = keywords))
                editCategoryId = null
            },
        )
    }
    categories.firstOrNull { it.id == confirmDeleteCategoryId }?.let { category ->
        ConfirmDialog(
            title = "Delete \"${category.name}\"?",
            body = "Articles stop being tagged with it and its match terms are lost.",
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteCategoryId = null },
            onConfirm = {
                vm.deleteCategory(category)
                confirmDeleteCategoryId = null
            },
        )
    }
}
