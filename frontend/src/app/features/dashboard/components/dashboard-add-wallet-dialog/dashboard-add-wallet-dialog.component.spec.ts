import { ComponentFixture, TestBed } from '@angular/core/testing';

import {
  DashboardAddWalletDialogComponent,
  EvmNetworkPresentation,
} from './dashboard-add-wallet-dialog.component';
import { EvmNetworkId } from '../../../../core/models/wallet-api.models';

describe('DashboardAddWalletDialogComponent', () => {
  let fixture: ComponentFixture<DashboardAddWalletDialogComponent>;
  let component: DashboardAddWalletDialogComponent;

  const supportedNetworks: ReadonlyArray<EvmNetworkId> = ['ETHEREUM'];
  const networkPresentation: ReadonlyArray<EvmNetworkPresentation> = [
    { id: 'ETHEREUM', icon: '⟠', label: 'Ethereum', color: '#627EEA' },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardAddWalletDialogComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardAddWalletDialogComponent);
    component = fixture.componentInstance;
    component.isOpen = true;
    component.isSubmitBusy = false;
    component.submitState = 'idle';
    component.submitMessage = null;
    component.existingWallets = [];
    component.supportedEvmNetworks = supportedNetworks;
    component.evmNetworksPresentation = networkPresentation;
    fixture.detectChanges();
  });

  it('accepts EVM address with 0X prefix', () => {
    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(
      '0X1A87f12aC07E9746e9B053B8D7EF1d45270D693f'
    );
    fixture.detectChanges();

    expect(component.canSubmitWallets()).toBeTrue();
  });

  it('splits pasted addresses by comma/newline and expands rows', () => {
    const first = '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f';
    const second = '0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045';
    const preventDefault = jasmine.createSpy('preventDefault');
    const pasteEvent = {
      clipboardData: { getData: () => `${first},\n${second}` },
      preventDefault,
    } as unknown as ClipboardEvent;

    component.onAddressPaste(pasteEvent, 0);
    fixture.detectChanges();

    expect(preventDefault).toHaveBeenCalled();
    expect(component.addWalletsForm.controls.wallets.length).toBe(2);
    expect(component.addWalletsForm.controls.wallets.controls[0].controls.address.value).toBe(first);
    expect(component.addWalletsForm.controls.wallets.controls[1].controls.address.value).toBe(second);
    expect(component.canSubmitWallets()).toBeTrue();
  });

  it('accepts Solana base58 address and assigns SOLANA network only', () => {
    const solAddress = '9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG';
    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(solAddress);
    fixture.detectChanges();

    expect(component.canSubmitWallets()).toBeTrue();

    let submitted: ReadonlyArray<{ address: string; networks: ReadonlyArray<string> }> = [];
    component.submitWallets.subscribe((items) => {
      submitted = items;
    });
    component.onSubmit();

    expect(submitted).toEqual([
      jasmine.objectContaining({
        address: solAddress,
        networks: ['SOLANA'],
      }),
    ]);
  });
});
