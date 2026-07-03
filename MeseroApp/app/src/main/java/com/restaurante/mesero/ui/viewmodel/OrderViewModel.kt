package com.restaurante.mesero.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.restaurante.mesero.data.local.entity.CategoriaEntity
import com.restaurante.mesero.data.local.entity.EstadoMesa
import com.restaurante.mesero.data.local.entity.ItemPedidoEntity
import com.restaurante.mesero.data.local.entity.MesaEntity
import com.restaurante.mesero.data.local.entity.PedidoEntity
import com.restaurante.mesero.data.local.entity.ProductoEntity
import com.restaurante.mesero.data.repository.ConfiguracionRepository
import com.restaurante.mesero.data.repository.MenuRepository
import com.restaurante.mesero.data.repository.MesaRepository
import com.restaurante.mesero.data.repository.PedidoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class OrderUiState(
    val mesa: MesaEntity? = null,
    val pedido: PedidoEntity? = null,
    val items: List<ItemPedidoEntity> = emptyList(),
    val categorias: List<CategoriaEntity> = emptyList(),
    val productosFiltrados: List<ProductoEntity> = emptyList(),
    val categoriaSeleccionadaId: Long? = null,
    val textoBusqueda: String = "",
    val cargando: Boolean = true,
    val error: String? = null,
    val moneda: String = "Bs"
) {
    val subtotal: Double get() = items.sumOf { it.subtotal }
}

val OBSERVACIONES_RAPIDAS = listOf(
    "Sin cebolla", "Sin picante", "Poco aceite", "Extra queso", "Bien cocido", "Término medio"
)

@OptIn(ExperimentalCoroutinesApi::class)
class OrderViewModel(
    private val mesaRepository: MesaRepository,
    private val menuRepository: MenuRepository,
    private val pedidoRepository: PedidoRepository,
    private val configuracionRepository: ConfiguracionRepository
) : ViewModel() {

    private var mesaIdActual: Long = -1L

    private val _uiState = MutableStateFlow(OrderUiState())
    val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()

    private val _pedidoId = MutableStateFlow<Long?>(null)
    private val _categoriaSeleccionada = MutableStateFlow<Long?>(null)
    private val _textoBusqueda = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                _pedidoId,
                _categoriaSeleccionada,
                _textoBusqueda
            ) { pedidoId, catId, texto -> Triple(pedidoId, catId, texto) }
                .flatMapLatest { (pedidoId, catId, texto) ->
                    val itemsFlow = if (pedidoId == null)
                        flowOf(emptyList<ItemPedidoEntity>())
                    else
                        pedidoRepository.observarItems(pedidoId)

                    val pedidoFlow = if (pedidoId == null)
                        flowOf<PedidoEntity?>(null)
                    else
                        pedidoRepository.observarPedido(pedidoId)

                    val productosFlow = when {
                        texto.isNotBlank() -> menuRepository.buscarProductos(texto)
                        catId != null -> menuRepository.observarProductosPorCategoria(catId)
                        else -> menuRepository.observarProductos()
                    }

                    combine(
                        itemsFlow,
                        pedidoFlow,
                        productosFlow,
                        menuRepository.observarCategorias(),
                        configuracionRepository.observar()
                    ) { items, pedido, productos, categorias, config ->
                        _uiState.value.copy(
                            pedido = pedido,
                            items = items,
                            productosFiltrados = productos,
                            categorias = categorias,
                            categoriaSeleccionadaId = catId,
                            textoBusqueda = texto,
                            moneda = config.moneda
                        )
                    }
                }
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        cargando = false
                    )
                }
                .collect { nuevoEstado ->
                    _uiState.value = nuevoEstado
                }
        }
    }

    fun inicializar(mesaId: Long, nombreMesero: String) {
        if (mesaIdActual == mesaId) return
        mesaIdActual = mesaId

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(cargando = true, error = null)

                val mesa = mesaRepository.obtenerMesa(mesaId)
                if (mesa == null) {
                    _uiState.value = _uiState.value.copy(
                        cargando = false,
                        error = "Mesa no encontrada"
                    )
                    return@launch
                }

                if (mesa.estado == EstadoMesa.LIBRE || mesa.estado == EstadoMesa.ESPERANDO_PEDIDO) {
                    mesaRepository.abrirMesa(mesa, nombreMesero)
                }

                val pedido = pedidoRepository.obtenerOCrearPedidoAbierto(mesa, nombreMesero)
                _pedidoId.value = pedido.id

                mesaRepository.observarMesa(mesaId)
                    .catch { /* ignorar errores de observación */ }
                    .collect { mesaActualizada ->
                        _uiState.value = _uiState.value.copy(
                            mesa = mesaActualizada,
                            cargando = false
                        )
                    }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    cargando = false,
                    error = "Error al abrir mesa: ${e.message}"
                )
            }
        }
    }

    fun seleccionarCategoria(categoriaId: Long?) {
        _categoriaSeleccionada.value = categoriaId
        _textoBusqueda.value = ""
    }

    fun buscar(texto: String) {
        _textoBusqueda.value = texto
        if (texto.isNotBlank()) _categoriaSeleccionada.value = null
    }

    fun agregarProducto(producto: ProductoEntity, cantidad: Int = 1, observaciones: String? = null) {
        val pedido = _uiState.value.pedido ?: return
        viewModelScope.launch {
            try {
                pedidoRepository.agregarProducto(pedido, producto, cantidad, observaciones)
                val mesa = _uiState.value.mesa
                if (mesa != null && mesa.estado == EstadoMesa.ESPERANDO_PEDIDO) {
                    mesaRepository.actualizarEstado(mesa, EstadoMesa.OCUPADA)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Error al agregar producto")
            }
        }
    }

    fun cambiarCantidad(item: ItemPedidoEntity, nuevaCantidad: Int) {
        viewModelScope.launch {
            try { pedidoRepository.cambiarCantidad(item, nuevaCantidad) } catch (_: Exception) {}
        }
    }

    fun actualizarObservaciones(item: ItemPedidoEntity, observaciones: String) {
        viewModelScope.launch {
            try { pedidoRepository.actualizarObservaciones(item, observaciones) } catch (_: Exception) {}
        }
    }

    fun eliminarItem(item: ItemPedidoEntity) {
        viewModelScope.launch {
            try { pedidoRepository.eliminarItem(item) } catch (_: Exception) {}
        }
    }

    fun duplicarItem(item: ItemPedidoEntity) {
        viewModelScope.launch {
            try { pedidoRepository.duplicarItem(item) } catch (_: Exception) {}
        }
    }

    fun marcarEsperandoCuenta() {
        val mesa = _uiState.value.mesa ?: return
        viewModelScope.launch {
            try { mesaRepository.actualizarEstado(mesa, EstadoMesa.ESPERANDO_CUENTA) } catch (_: Exception) {}
        }
    }
}
