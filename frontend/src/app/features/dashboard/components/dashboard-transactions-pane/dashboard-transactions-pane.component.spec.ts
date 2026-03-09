import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DashboardTransactionsPaneComponent } from './dashboard-transactions-pane.component';
import { TransactionItem, WalletInfo, NetworkInfo } from '../../../../core/models/dashboard.models';

describe('DashboardTransactionsPaneComponent', () => {
  let fixture: ComponentFixture<DashboardTransactionsPaneComponent>;
  let component: DashboardTransactionsPaneComponent;

  const wallets: ReadonlyArray<WalletInfo> = [
    {
      id: '0xwallet-a',
      label: 'Wallet A',
      address: '0xwallet-a',
      color: '#22d3ee',
    },
  ];

  const networks: ReadonlyArray<NetworkInfo> = [
    {
      id: 'ETHEREUM',
      icon: '⟠',
      label: 'Ethereum',
      color: '#627EEA',
    },
    {
      id: 'ARBITRUM',
      icon: '△',
      label: 'Arbitrum',
      color: '#28A0F0',
    },
  ];

  const transactions: ReadonlyArray<TransactionItem> = [
    {
      id: 'tx-review',
      hash: '0xreview',
      timestamp: '2026-03-06T10:00:00Z',
      type: 'EXTERNAL_INBOUND',
      symbol: 'USDC',
      networkId: 'ARBITRUM',
      walletId: '0xwallet-a',
      status: 'CONFIRMED',
      issue: null,
      bridgeStatus: 'REVIEW',
      hasOverride: false,
      flows: [
        {
          role: 'TRANSFER',
          symbol: 'USDC',
          quantity: 100,
          signedQuantity: 100,
          priceUsd: 1,
          source: 'STABLECOIN',
        },
      ],
    },
    {
      id: 'tx-matched',
      hash: '0xmatched',
      timestamp: '2026-03-06T09:00:00Z',
      type: 'EXTERNAL_TRANSFER_OUT',
      symbol: 'USDC',
      networkId: 'ETHEREUM',
      walletId: '0xwallet-a',
      status: 'CONFIRMED',
      issue: null,
      bridgeStatus: 'MATCHED',
      hasOverride: false,
      flows: [
        {
          role: 'TRANSFER',
          symbol: 'USDC',
          quantity: 100,
          signedQuantity: -100,
          priceUsd: 1,
          source: 'STABLECOIN',
        },
      ],
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardTransactionsPaneComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardTransactionsPaneComponent);
    component = fixture.componentInstance;
    component.wallets = wallets;
    component.networks = networks;
    component.selectedWalletIds = new Set();
    component.selectedNetworkIds = new Set();
    component.sourceTransactions = transactions;
    component.isReadOnly = true;
    fixture.detectChanges();
  });

  it('renders review bridge badge and copy for expanded review transaction', () => {
    const rows = fixture.nativeElement.querySelectorAll('.tx-row');
    rows[0].click();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('REVIEW');
    expect(text).toContain('Bridge matching is ambiguous.');
  });

  it('filters transactions by bridge status chip', () => {
    const chips = [...fixture.nativeElement.querySelectorAll('.bridge-filter-chip')] as HTMLButtonElement[];
    const reviewChip = chips.find((chip) => chip.textContent?.trim() === 'REVIEW');

    expect(reviewChip).toBeDefined();
    reviewChip?.click();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('0xreview');
    expect(text).not.toContain('0xmatched');
  });
});
