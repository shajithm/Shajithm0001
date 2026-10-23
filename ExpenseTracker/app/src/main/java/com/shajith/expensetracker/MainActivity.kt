package com.shajith.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shajith.expensetracker.ui.AppViewModel
import com.shajith.expensetracker.ui.screens.LoginScreen
import com.shajith.expensetracker.ui.screens.MainScaffold
import com.shajith.expensetracker.ui.theme.ExpenseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExpenseTheme {
                val vm: AppViewModel = viewModel()
                val user by vm.user.collectAsStateWithLifecycle()
                if (user == null) LoginScreen(vm) else MainScaffold(vm)
            }
        }
    }
}
