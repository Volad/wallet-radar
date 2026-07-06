import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { LpDataService } from '../../core/services/lp-data.service';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { LpPageComponent } from './lp-page.component';

describe('LpPageComponent', () => {
  let component: LpPageComponent;
  let fixture: ComponentFixture<LpPageComponent>;
  let lpDataServiceSpy: jasmine.SpyObj<LpDataService>;

  beforeEach(async () => {
    lpDataServiceSpy = jasmine.createSpyObj<LpDataService>('LpDataService', ['getSessionLp', 'mapPosition']);
    lpDataServiceSpy.getSessionLp.and.returnValue(of({
      sessionId: '549b0aba-a9af-4789-b125-ebb86314a3f1',
      summary: {
        activeTvlUsd: 0,
        feesEarnedUsd: 0,
        unclaimedUsd: 0,
        inRange: 0,
        outOfRange: 0,
        realizedPnlUsd: null,
      },
      positions: [],
    }));

    await TestBed.configureTestingModule({
      imports: [LpPageComponent],
      providers: [
        { provide: LpDataService, useValue: lpDataServiceSpy },
        { provide: WalletApiService, useValue: jasmine.createSpyObj('WalletApiService', ['refreshLpPosition']) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LpPageComponent);
    component = fixture.componentInstance;
  });

  it('creates component', () => {
    expect(component).toBeTruthy();
  });

  it('loads LP data when session id is provided', () => {
    component.sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    component.ngOnChanges({
      sessionId: {
        previousValue: null,
        currentValue: component.sessionId,
        firstChange: true,
        isFirstChange: () => true,
      },
    });

    expect(lpDataServiceSpy.getSessionLp).toHaveBeenCalledWith('549b0aba-a9af-4789-b125-ebb86314a3f1', 'active');
    expect(component.viewState().status).toBe('success');
  });
});
