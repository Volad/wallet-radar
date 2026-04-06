import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DashboardTransactionsPaneComponent } from './dashboard-transactions-pane.component';
import { NetworkInfo, TransactionItem, WalletInfo } from '../../../../core/models/dashboard.models';

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
  ];

  const transactions: ReadonlyArray<TransactionItem> = [
    {
      id: 'tx-1',
      hash: '0xreview',
      timestamp: '2026-03-06T10:00:00Z',
      type: 'EXTERNAL_INBOUND',
      symbol: 'USDC',
      networkId: 'ETHEREUM',
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
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardTransactionsPaneComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardTransactionsPaneComponent);
    component = fixture.componentInstance;
    component.wallets = wallets;
    component.networks = networks;
    component.sourceTransactions = transactions;
    component.totalCount = 101;
    component.page = 1;
    component.pageSize = 50;
    component.isReadOnly = true;
    fixture.detectChanges();
  });

  it('renders paging metadata from server response', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('101 txns');
    expect(text).toContain('51-51 of 101');
    expect(text).toContain('Page 2 / 3');
  });

  it('emits search and filter changes', () => {
    const searchSpy = jasmine.createSpy('search');
    const bridgeSpy = jasmine.createSpy('bridge');
    const spamSpy = jasmine.createSpy('spam');

    component.transactionSearchChange.subscribe(searchSpy);
    component.bridgeStatusFilterChange.subscribe(bridgeSpy);
    component.spamFilterChange.subscribe(spamSpy);

    const searchInput = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    searchInput.value = 'eth';
    searchInput.dispatchEvent(new Event('input'));

    const chips = [...fixture.nativeElement.querySelectorAll('.bridge-filter-chip')] as HTMLButtonElement[];
    chips.find((chip) => chip.textContent?.trim() === 'REVIEW')?.click();
    chips.find((chip) => chip.textContent?.trim() === 'SPAM')?.click();
    fixture.detectChanges();

    expect(searchSpy).toHaveBeenCalledWith('eth');
    expect(bridgeSpy).toHaveBeenCalledWith('REVIEW');
    expect(spamSpy).toHaveBeenCalledWith('SPAM_ONLY');
  });

  it('emits page changes', () => {
    const pageSpy = jasmine.createSpy('page');
    component.pageChange.subscribe(pageSpy);

    const nextButton = [...fixture.nativeElement.querySelectorAll('.page-btn')] as HTMLButtonElement[];
    nextButton[1].click();

    expect(pageSpy).toHaveBeenCalledWith(2);
  });

  it('does not show PRICE label for transfer-only rows without missing_price issue', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).not.toContain('PRICE?');
  });

  it('renders external counterparty text without EX badge on receiving side', () => {
    component.sourceTransactions = [
      {
        ...transactions[0],
        type: 'EXTERNAL_INBOUND',
        matchedCounterparty: 'BYBIT:33625378',
      },
    ];
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('From');
    expect(text).toContain('BYBIT:33625378');

    const metaBadge = fixture.nativeElement.querySelector('.tx-meta-external-anchor') as HTMLElement | null;
    expect(metaBadge).toBeNull();
  });

  it('renders external-ledger badge only in meta column for bybit-side transfer rows', () => {
    component.sourceTransactions = [
      {
        ...transactions[0],
        type: 'EXTERNAL_TRANSFER_OUT',
        walletId: 'BYBIT:33625378',
        matchedCounterparty: '0xwallet-a',
      },
    ];
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('To');
    expect(text).toContain('0xwallet-a');
    expect(text).toContain('BYBIT:33625378');

    const metaBadge = fixture.nativeElement.querySelector('.tx-meta-external-anchor') as HTMLElement | null;
    expect(metaBadge).not.toBeNull();
    expect(metaBadge?.getAttribute('data-tooltip')).toBe('BYBIT:33625378');

    const counterpartyBadge = fixture.nativeElement.querySelector('.tx-counterparty-row .tx-external-anchor') as HTMLElement | null;
    expect(counterpartyBadge).toBeNull();
  });
});
