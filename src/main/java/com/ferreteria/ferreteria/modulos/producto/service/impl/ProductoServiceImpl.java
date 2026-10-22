package com.ferreteria.ferreteria.modulos.producto.service.impl;

import com.ferreteria.ferreteria.excepciones.ResourceNotFoundException;
import com.ferreteria.ferreteria.modulos.producto.dto.CatalogoDTO;
import com.ferreteria.ferreteria.modulos.producto.dto.ProductoDTO;
import com.ferreteria.ferreteria.modulos.producto.model.Categoria;
import com.ferreteria.ferreteria.modulos.producto.model.Marca;
import com.ferreteria.ferreteria.modulos.producto.model.Producto;
import com.ferreteria.ferreteria.modulos.producto.model.Unidad;
import com.ferreteria.ferreteria.modulos.producto.repository.CategoriaRepository;
import com.ferreteria.ferreteria.modulos.producto.repository.MarcaRepository;
import com.ferreteria.ferreteria.modulos.producto.repository.ProductoRepository;
import com.ferreteria.ferreteria.modulos.producto.repository.UnidadRepository;
import com.ferreteria.ferreteria.modulos.producto.service.ProductoService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ferreteria.ferreteria.modulos.seguridad.service.BitacoraService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;


@Service
public class ProductoServiceImpl implements ProductoService {

    private final ProductoRepository productoRepository;
    private final CategoriaRepository categoriaRepository;
    private final MarcaRepository marcaRepository;
    private final UnidadRepository unidadRepository;
    private final BitacoraService bitacora;

    public ProductoServiceImpl(ProductoRepository productoRepository,
                               CategoriaRepository categoriaRepository,
                               MarcaRepository marcaRepository,
                               UnidadRepository unidadRepository, 
                               BitacoraService bitacora){
        this.productoRepository = productoRepository;
        this.categoriaRepository = categoriaRepository;
        this.marcaRepository = marcaRepository;
        this.unidadRepository = unidadRepository;
        this.bitacora = bitacora;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductoDTO> listarTodos() {
        return productoRepository.findAllConRelaciones().stream()
                .map(this::convertirADTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductoDTO> listarActivos() {
        return productoRepository.findByEstadoConRelaciones("ACTIVO").stream()
                .map(this::convertirADTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductoDTO obtenerPorId(Long id) {
        Producto producto = productoRepository.findByIdConRelaciones(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));
        return convertirADTO(producto);
    }

    @Override
    @Transactional
    public ProductoDTO guardar(ProductoDTO dto) {
        normalizar(dto);
        validarPrecios(dto.getPrecio_compra(), dto.getPrecio_venta());

        if (productoRepository.existsByCodigo(dto.getCodigo())) {
            throw new IllegalArgumentException("Ya existe un producto con el código: " + dto.getCodigo());
        }

        Producto producto = new Producto();
        producto.setCodigo(dto.getCodigo());
        producto.setNombre(dto.getNombre());
        producto.setDescripcion(dto.getDescripcion());
        producto.setCategoria(buscarCategoriaActiva(dto.getId_categoria()));
        producto.setMarca(buscarMarcaActiva(dto.getId_marca()));
        producto.setUnidad(buscarUnidad(dto.getId_unidad()));
        producto.setPrecio_compra(dto.getPrecio_compra());
        producto.setPrecio_venta(dto.getPrecio_venta());
        producto.setStock_minimo(dto.getStock_minimo());
        producto.setEstado(dto.getEstado() != null ? dto.getEstado() : "ACTIVO");
        // stock_actual NO se asigna: MySQL lo deja en 0 y luego lo suben las compras

        Producto guardado = productoRepository.save(producto);
        bitacora.registrar("PRODUCTOS", "CREAR", "Creó el producto " + guardado.getCodigo());
        return convertirADTO(guardado);
    }

    @Override
    @Transactional
    public ProductoDTO actualizar(Long id, ProductoDTO dto) {
        normalizar(dto);
        validarPrecios(dto.getPrecio_compra(), dto.getPrecio_venta());

        Producto producto = productoRepository.findByIdConRelaciones(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));

        if (productoRepository.existsByCodigoEnOtroProducto(dto.getCodigo(), id)) {
            throw new IllegalArgumentException("El código " + dto.getCodigo() + " ya está en uso por otro producto.");
        }

        // Solo se exige que esté ACTIVA si se cambia a otra categoría/marca.
        // Así un producto antiguo puede editarse aunque su categoría actual se haya inactivado.
        if (!Objects.equals(producto.getCategoria().getId_categoria(), dto.getId_categoria())) {
            producto.setCategoria(buscarCategoriaActiva(dto.getId_categoria()));
        }
        if (!Objects.equals(producto.getMarca().getId_marca(), dto.getId_marca())) {
            producto.setMarca(buscarMarcaActiva(dto.getId_marca()));
        }
        if (!Objects.equals(producto.getUnidad().getId_unidad(), dto.getId_unidad())) {
            producto.setUnidad(buscarUnidad(dto.getId_unidad()));
        }

        StringBuilder c = new StringBuilder();
        if (!java.util.Objects.equals(producto.getNombre(), dto.getNombre()))
            c.append("Nombre: ").append(producto.getNombre()).append(" → ").append(dto.getNombre()).append("; ");
        if (!java.util.Objects.equals(producto.getCodigo(), dto.getCodigo()))
            c.append("Código: ").append(producto.getCodigo()).append(" → ").append(dto.getCodigo()).append("; ");
        if (producto.getPrecio_compra().compareTo(dto.getPrecio_compra()) != 0)
            c.append("Precio de Compra: ").append(producto.getPrecio_compra()).append(" → ").append(dto.getPrecio_compra()).append("; ");
        if (producto.getPrecio_venta().compareTo(dto.getPrecio_venta()) != 0)
            c.append("Precio de Venta: ").append(producto.getPrecio_venta()).append(" → ").append(dto.getPrecio_venta()).append("; ");
        if (producto.getStock_minimo().compareTo(dto.getStock_minimo()) != 0)
            c.append("Stock mínimo: ").append(producto.getStock_minimo()).append(" → ").append(dto.getStock_minimo()).append("; ");
        if (!java.util.Objects.equals(producto.getEstado(), dto.getEstado()))
            c.append("Estado: ").append(producto.getEstado()).append(" → ").append(dto.getEstado()).append("; ");

        producto.setCodigo(dto.getCodigo());
        producto.setNombre(dto.getNombre());
        producto.setDescripcion(dto.getDescripcion());
        producto.setPrecio_compra(dto.getPrecio_compra());
        producto.setPrecio_venta(dto.getPrecio_venta());
        producto.setStock_minimo(dto.getStock_minimo());
        // stock_actual se ignora aunque venga en el JSON

        if (dto.getEstado() != null && !dto.getEstado().isBlank()) {
            if (!dto.getEstado().matches("^(ACTIVO|INACTIVO)$")) {
                throw new IllegalArgumentException("El estado debe ser ACTIVO o INACTIVO");
            }
            producto.setEstado(dto.getEstado());
        }

        Producto actualizado = productoRepository.save(producto);
        bitacora.registrar("PRODUCTOS", "EDITAR",
                "Editó " + dto.getCodigo() + ". " + (c.length() == 0 ? "Sin cambios." : c.toString()));
        return convertirADTO(actualizado);
    }

    @Override
    @Transactional
    public void cambiarEstado(Long id, String nuevoEstado) {
        if (nuevoEstado == null || !nuevoEstado.toUpperCase().matches("^(ACTIVO|INACTIVO)$")) {
            throw new IllegalArgumentException("El estado debe ser ACTIVO o INACTIVO");
        }
        Producto producto = productoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));
        producto.setEstado(nuevoEstado.toUpperCase());
        productoRepository.save(producto);
    }

    @Override
    @Transactional
    public void eliminar(Long id) {
        Producto producto = productoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));

        if (producto.getStock_actual() != null && producto.getStock_actual().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException(
                    "No se puede eliminar un producto con stock. Cámbielo a INACTIVO.");
        }
        try {
            productoRepository.delete(producto);
            bitacora.registrar("PRODUCTOS", "ELIMINAR", "Eliminó el producto " + producto.getCodigo());
            productoRepository.flush(); // Fuerza el DELETE aquí para capturar las FK (compras, ventas, kardex)
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(
                    "No se puede eliminar el producto porque tiene movimientos registrados. Cámbielo a INACTIVO.");
        }
    }

    // =========================================================================
    // CATÁLOGOS
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<CatalogoDTO> listarCategorias() {
        return categoriaRepository.findByEstadoOrderByNombreAsc("ACTIVO").stream()
                .map(c -> new CatalogoDTO(c.getId_categoria(), null, c.getNombre()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogoDTO> listarMarcas() {
        return marcaRepository.findByEstadoOrderByNombreAsc("ACTIVO").stream()
                .map(m -> new CatalogoDTO(m.getId_marca(), null, m.getNombre()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogoDTO> listarUnidades() {
        return unidadRepository.findAllByOrderByNombreAsc().stream()
                .map(u -> new CatalogoDTO(u.getId_unidad(), u.getCodigo(), u.getNombre()))
                .toList();
    }

    // =========================================================================
    // MÉTODOS PRIVADOS
    // =========================================================================

    private Categoria buscarCategoriaActiva(Integer id) {
        Categoria categoria = categoriaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Categoría no encontrada con ID: " + id));
        if (!"ACTIVO".equals(categoria.getEstado())) {
            throw new IllegalArgumentException("La categoría '" + categoria.getNombre() + "' está inactiva");
        }
        return categoria;
    }

    private Marca buscarMarcaActiva(Integer id) {
        Marca marca = marcaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + id));
        if (!"ACTIVO".equals(marca.getEstado())) {
            throw new IllegalArgumentException("La marca '" + marca.getNombre() + "' está inactiva");
        }
        return marca;
    }

    private Unidad buscarUnidad(Integer id) {
        return unidadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Unidad de medida no encontrada con ID: " + id));
    }

    /** Regla de negocio: no se vende por debajo del costo */
    private void validarPrecios(BigDecimal precioCompra, BigDecimal precioVenta) {
        if (precioCompra != null && precioVenta != null && precioVenta.compareTo(precioCompra) < 0) {
            throw new IllegalArgumentException("El precio de venta no puede ser menor al precio de compra");
        }
    }

    private void normalizar(ProductoDTO dto) {
        if (dto.getCodigo() != null) dto.setCodigo(dto.getCodigo().trim().toUpperCase());
        if (dto.getNombre() != null) dto.setNombre(dto.getNombre().trim());
        if (dto.getDescripcion() != null) dto.setDescripcion(dto.getDescripcion().trim());
    }

    /** Misma lógica que la vista v_alerta_stock */
    private String calcularNivelStock(BigDecimal stockActual, BigDecimal stockMinimo) {
        BigDecimal actual = (stockActual != null) ? stockActual : BigDecimal.ZERO;
        BigDecimal minimo = (stockMinimo != null) ? stockMinimo : BigDecimal.ZERO;
        if (actual.compareTo(BigDecimal.ZERO) <= 0) return "AGOTADO";
        if (actual.compareTo(minimo) <= 0) return "STOCK_MINIMO";
        return "OK";
    }

    private ProductoDTO convertirADTO(Producto producto) {
        ProductoDTO dto = new ProductoDTO();
        dto.setId_producto(producto.getId_producto());
        dto.setCodigo(producto.getCodigo());
        dto.setNombre(producto.getNombre());
        dto.setDescripcion(producto.getDescripcion());

        dto.setId_categoria(producto.getCategoria().getId_categoria());
        dto.setCategoria(producto.getCategoria().getNombre());
        dto.setId_marca(producto.getMarca().getId_marca());
        dto.setMarca(producto.getMarca().getNombre());
        dto.setId_unidad(producto.getUnidad().getId_unidad());
        dto.setUnidad(producto.getUnidad().getNombre());
        dto.setUnidad_codigo(producto.getUnidad().getCodigo());

        dto.setPrecio_compra(producto.getPrecio_compra());
        dto.setPrecio_venta(producto.getPrecio_venta());
        dto.setStock_actual(producto.getStock_actual());
        dto.setStock_minimo(producto.getStock_minimo());
        dto.setNivel_stock(calcularNivelStock(producto.getStock_actual(), producto.getStock_minimo()));

        dto.setEstado(producto.getEstado());
        dto.setFec_registro(producto.getFec_registro());
        dto.setFec_modifica(producto.getFec_modifica());
        return dto;
    }
}
