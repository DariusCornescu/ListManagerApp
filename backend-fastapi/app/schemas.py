from decimal import Decimal
from pydantic import BaseModel, EmailStr, ConfigDict, Field
from typing import Optional
from datetime import datetime

# Match PostgreSQL INTEGER and Android's signed 32-bit quantity/version fields.
SESSION_INT_MAX = 2**31 - 1

# ===== Distributor Schemas =====
class DistributorBase(BaseModel):
    distributor_name: str

class DistributorCreate(DistributorBase):
    pass

class DistributorDTO(DistributorBase):
    id: int
    created_at: datetime
    
    model_config = ConfigDict(from_attributes=True)

# ===== Product Schemas =====
class ProductBase(BaseModel):
    name: str
    distributor_id: int
    aliases: Optional[str] = None
    price: Optional[Decimal] = Field(default=None, ge=0)

class ProductCreate(ProductBase):
    pass

class ProductDTO(ProductBase):
    id: int
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)

class ProductPublicDTO(BaseModel):
    """A product as anyone may see it. Purchase prices are admin-only."""
    id: int
    name: str
    distributor_id: int
    aliases: Optional[str] = None
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)

class AdminProductPageDTO(BaseModel):
    items: list[ProductDTO]
    total: int
    limit: int
    offset: int

# ===== Session Schemas =====
class GlobalSessionBase(BaseModel):
    name: str

class GlobalSessionCreate(GlobalSessionBase):
    # Optional: when set, creates a team-owned session (caller must be a member).
    team_id: Optional[int] = None
    client_uuid: Optional[str] = Field(default=None, max_length=36)

class GlobalSessionDTO(GlobalSessionBase):
    id: int
    is_active: bool
    created_at: datetime
    completed_at: Optional[datetime] = None
    version: int
    owner_user_id: Optional[int] = None
    team_id: Optional[int] = None
    client_uuid: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)

# ===== Session Item Schemas =====
class GlobalSessionItemBase(BaseModel):
    session_id: int
    product_id: int
    quantity: int = 1

class GlobalSessionItemCreate(BaseModel):
    product_id: int
    quantity: int = Field(default=1, ge=1, le=SESSION_INT_MAX)
    idempotency_key: Optional[str] = Field(default=None, max_length=64)

class GlobalSessionItemUpdate(BaseModel):
    # Preserve legacy zero PUT behavior; Android removes rows using DELETE.
    quantity: int = Field(ge=0, le=SESSION_INT_MAX)
    version: int = Field(ge=0, le=SESSION_INT_MAX)
    idempotency_key: Optional[str] = Field(default=None, max_length=64)

class GlobalSessionItemDTO(GlobalSessionItemBase):
    id: int
    version: int
    created_at: datetime
    updated_at: datetime
    
    product_name: Optional[str] = None
    distributor_name: Optional[str] = None
    item_uuid: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)

# ===== Auth Schemas =====
class UserCreate(BaseModel):
    username: str
    email: EmailStr
    password: str

class UserLogin(BaseModel):
    username: str
    password: str

class UserUpdate(BaseModel):
    email: Optional[EmailStr] = None
    password: Optional[str] = None
    # Profile: a missing field is left alone, a blank one clears the value.
    full_name: Optional[str] = Field(default=None, max_length=100)
    company: Optional[str] = Field(default=None, max_length=100)
    address: Optional[str] = Field(default=None, max_length=255)

class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    # Additive: clients predating refresh ignore it and re-login after expiry.
    refresh_token: Optional[str] = None

class RefreshRequest(BaseModel):
    refresh_token: str = Field(max_length=128)

class UserDTO(BaseModel):
    id: int
    username: str
    email: str
    role: str
    is_active: bool
    full_name: Optional[str] = None
    company: Optional[str] = None
    address: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)

# ===== Catalog Import Schemas =====
class ImportRowErrorDTO(BaseModel):
    line: int
    reason: str


class ImportResultDTO(BaseModel):
    new: int
    updated: int
    unchanged: int
    committed: bool
    errors: list[ImportRowErrorDTO]


# ===== Crash Reporting Schemas =====
class CrashReportCreate(BaseModel):
    event_id: Optional[str] = Field(default=None, max_length=100)
    app_version: Optional[str] = Field(default=None, max_length=50)
    android_version: Optional[str] = Field(default=None, max_length=50)
    device: Optional[str] = Field(default=None, max_length=100)
    username: Optional[str] = Field(default=None, max_length=50)
    stacktrace: str = Field(min_length=1, max_length=20000)


class CrashReportDTO(CrashReportCreate):
    id: int
    signature: str
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class CrashGroupDTO(BaseModel):
    signature: str
    count: int
    affected_users: int
    triage_score: int
    first_seen: datetime
    last_seen: datetime
    app_versions: list[str]
    devices: list[str]
    reports: list[CrashReportDTO]


# ===== Presence Schemas =====
class PresenceUserDTO(BaseModel):
    user_id: int
    username: str


# ===== Admin Dashboard Schemas =====
class StoreDTO(BaseModel):
    """A 'store' on the admin dashboard = a Team (workspace) with headcount."""
    id: int
    name: str
    member_count: int
    created_at: Optional[datetime] = None
    lists_completed: int = 0


class ActivityDayDTO(BaseModel):
    date: str  # ISO YYYY-MM-DD
    lists_completed: int
    items_added: int
    users_registered: int = 0
    teams_created: int = 0
    # Distinct people who changed a list on the server that day.
    active_users: int = 0
    crash_reports: int = 0
    # One per distributor on each list completed that day, as one PDF is.
    documents: int = 0


class AdminDashboardDTO(BaseModel):
    stores: list[StoreDTO]
    users_count: int
    products_count: int
    distributors_count: int
    lists_completed_count: int
    documents_count: int = 0
    crashes_count: int
    devices_count: int
    activity: list[ActivityDayDTO]


class AdminUserTeamDTO(BaseModel):
    id: int
    name: str
    role: str


class AdminUserDetailDTO(BaseModel):
    """A user as admins see them. The postal address is left out on purpose."""
    id: int
    username: str
    email: str
    full_name: Optional[str] = None
    company: Optional[str] = None
    role: str
    is_active: bool
    created_at: Optional[datetime] = None
    teams: list[AdminUserTeamDTO]
    # Last list change on the server, not the last time the app was opened.
    last_activity_at: Optional[datetime] = None
    active_logins: int


class AdminOrderDTO(BaseModel):
    """A completed list. Which products it held is left out on purpose."""
    session_id: int
    name: str
    team_name: Optional[str] = None
    owner_username: Optional[str] = None
    completed_at: datetime
    item_lines: int
    total_quantity: int
    documents: int


class AdminOrderPageDTO(BaseModel):
    items: list[AdminOrderDTO]
    total: int
    limit: int
    offset: int


# ===== Inventory Schemas =====
INVENTORY_MAX_QUANTITY = 1_000_000_000
INVENTORY_MAX_PRICE_BANI = 1_000_000_000_000


class InventoryItemFields(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    product_id: Optional[int] = None
    # Either may be blank: the spoken line was incomplete and is filled in later.
    quantity: Optional[float] = Field(default=None, ge=0, le=INVENTORY_MAX_QUANTITY)
    price_bani: Optional[int] = Field(default=None, ge=0, le=INVENTORY_MAX_PRICE_BANI)


class InventoryItemCreate(InventoryItemFields):
    item_uuid: str = Field(min_length=1, max_length=36)


class InventoryItemUpdate(InventoryItemFields):
    version: int = Field(ge=0, le=SESSION_INT_MAX)
    idempotency_key: Optional[str] = Field(default=None, max_length=64)


class InventoryClear(BaseModel):
    idempotency_key: Optional[str] = Field(default=None, max_length=64)


class InventoryItemDTO(BaseModel):
    id: int
    inventory_id: int
    item_uuid: str
    name: str
    product_id: Optional[int] = None
    quantity: Optional[float] = None
    price_bani: Optional[int] = None
    version: int
    # True only on a retried add whose row has since been removed.
    deleted: bool = False

    model_config = ConfigDict(from_attributes=True)


class InventoryDTO(BaseModel):
    id: int
    owner_user_id: Optional[int] = None
    team_id: Optional[int] = None
    items: list[InventoryItemDTO]

    model_config = ConfigDict(from_attributes=True)
