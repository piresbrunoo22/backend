package br.com.tecloja.api.service.impl;

import br.com.tecloja.api.dto.ItemPedidoFormDTO;
import br.com.tecloja.api.dto.PedidoDTO;
import br.com.tecloja.api.dto.PedidoFormDTO;
import br.com.tecloja.api.exception.BusinessException;
import br.com.tecloja.api.exception.ResourceNotFoundException;
import br.com.tecloja.api.model.*;
import br.com.tecloja.api.repository.ClienteRepository;
import br.com.tecloja.api.repository.PedidoRepository;
import br.com.tecloja.api.repository.ProdutoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PedidoServiceImplTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @Mock
    private ClienteRepository clienteRepository;

    @Mock
    private ProdutoRepository produtoRepository;

    @InjectMocks
    private PedidoServiceImpl pedidoService;

    private Cliente cliente;
    private Produto notebook;
    private PedidoFormDTO pedidoFormDTO;

    @BeforeEach
    void setUp() {
        // Inicializa Cliente Falso
        cliente = new Cliente();
        cliente.setId(1L);
        cliente.setNome("Maria Silva");
        cliente.setEmail("maria@gmail.com");

        // Inicializa Produto Falso
        notebook = new Produto();
        notebook.setId(10L);
        notebook.setNome("Notebook Gamer");
        notebook.setPreco(new BigDecimal("5000.00"));
        notebook.setEstoque(5); // 5 unidades em estoque

        // Inicializa Formulário de Pedido (Checkout)
        ItemPedidoFormDTO itemForm = new ItemPedidoFormDTO(10L, 2); // Solicita 2 unidades
        pedidoFormDTO = new PedidoFormDTO(1L, List.of(itemForm));
    }

    @Test
    @DisplayName("Deve faturar pedido com sucesso quando houver estoque suficiente")
    void realizarPedido_Sucesso() {
        // Mocks do Repositório
        when(clienteRepository.findById(1L)).thenReturn(Optional.of(cliente));
        when(produtoRepository.findById(10L)).thenReturn(Optional.of(notebook));
        
        // Mock de Salvamento do Pedido (Simula gravação com ID auto-incremento)
        when(pedidoRepository.save(any(Pedido.class))).thenAnswer(invocation -> {
            Pedido p = invocation.getArgument(0);
            p.setId(100L); // Simula ID do banco
            p.getItens().get(0).setId(500L); // Simula ID do item do banco
            return p;
        });

        // Executa a regra
        PedidoDTO resultado = pedidoService.realizarPedido(pedidoFormDTO);

        // Validações
        assertNotNull(resultado);
        assertEquals(100L, resultado.id());
        assertEquals("PAGO", resultado.status());
        assertEquals(1L, resultado.clienteId());
        assertEquals("Maria Silva", resultado.clienteNome());
        assertEquals(1, resultado.itens().size());
        assertEquals(new BigDecimal("10000.00"), resultado.valorTotal()); // 2 unidades x R$ 5000.00

        // Valida redução do estoque físico da entidade
        assertEquals(3, notebook.getEstoque()); // 5 - 2 = 3

        // Verifica interações de escrita física no banco
        verify(produtoRepository, times(1)).save(notebook);
        verify(pedidoRepository, times(1)).save(any(Pedido.class));
    }

    @Test
    @DisplayName("Deve lançar BusinessException e não salvar nada no banco se estoque for insuficiente")
    void realizarPedido_EstoqueInsuficiente() {
        // Configura pedido com quantidade que excede o estoque (solicita 6, estoque é 5)
        ItemPedidoFormDTO itemExcedente = new ItemPedidoFormDTO(10L, 6);
        PedidoFormDTO formInvalido = new PedidoFormDTO(1L, List.of(itemExcedente));

        when(clienteRepository.findById(1L)).thenReturn(Optional.of(cliente));
        when(produtoRepository.findById(10L)).thenReturn(Optional.of(notebook));

        // Executa e valida o lançamento da BusinessException
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            pedidoService.realizarPedido(formInvalido);
        });

        assertTrue(exception.getMessage().contains("Estoque insuficiente"));

        // Garante que o estoque original não foi alterado
        assertEquals(5, notebook.getEstoque());

        // Garante que o método save NUNCA foi chamado para produto e pedido (rollback lógico)
        verify(produtoRepository, never()).save(any(Produto.class));
        verify(pedidoRepository, never()).save(any(Pedido.class));
    }

    @Test
    @DisplayName("Deve lançar ResourceNotFoundException se o cliente não existir no banco")
    void realizarPedido_ClienteNaoEncontrado() {
        when(clienteRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            pedidoService.realizarPedido(pedidoFormDTO);
        });

        verify(pedidoRepository, never()).save(any(Pedido.class));
    }
}
