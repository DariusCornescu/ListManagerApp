# app/models.py
from sqlalchemy import Column, Integer, String, Boolean, ForeignKey, DateTime, Text, func, UniqueConstraint, CheckConstraint, Index, Numeric
from sqlalchemy.orm import relationship
from .database import Base

class User(Base):
    __tablename__ = "users"
    
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, nullable=False, index=True)
    email = Column(String(100), unique=True, nullable=False, index=True)
    hashed_password = Column(String(200), nullable=False)
    role = Column(String(20), default="USER")   # Possible roles: USER, ADMIN
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=func.now())

class Distributor(Base):
    __tablename__ = "distributors"
    
    id = Column(Integer, primary_key=True, index=True)
    distributor_name = Column(String(100), unique=True, nullable=False, index=True)
    created_at = Column(DateTime, default=func.now())
    
    products = relationship("Product", back_populates="distributor")

class Product(Base):
    __tablename__ = "products"
    
    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(200), nullable=False, index=True)
    distributor_id = Column(Integer, ForeignKey("distributors.id"), nullable=False)
    aliases = Column(String, nullable=True)
    price = Column(Numeric(10, 2), nullable=True)
    created_at = Column(DateTime, default=func.now())
    distributor = relationship("Distributor", back_populates="products")

class Team(Base):
    __tablename__ = "teams"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    created_at = Column(DateTime, default=func.now())
    created_by = Column(Integer, ForeignKey("users.id"), nullable=True)

    members = relationship(
        "TeamMember",
        back_populates="team",
        cascade="all, delete-orphan",
    )
    sessions = relationship("GlobalSession", back_populates="team")


class TeamMember(Base):
    __tablename__ = "team_members"

    id = Column(Integer, primary_key=True, index=True)
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    role = Column(String(20), nullable=False, default="member")  # admin | member (TEAM-scoped)
    created_at = Column(DateTime, default=func.now())

    __table_args__ = (UniqueConstraint("team_id", "user_id"),)

    team = relationship("Team", back_populates="members")
    user = relationship("User")


class TeamInvite(Base):
    __tablename__ = "team_invites"

    id = Column(Integer, primary_key=True, index=True)
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=False)
    code = Column(String(32), unique=True, nullable=False, index=True)
    role = Column(String(20), default="member")
    created_by = Column(Integer, ForeignKey("users.id"))
    expires_at = Column(DateTime, nullable=False)
    accepted_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=func.now())


class GlobalSession(Base):
    __tablename__ = "global_sessions"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    is_active = Column(Boolean, default=True, index=True)
    created_at = Column(DateTime, default=func.now())
    completed_at = Column(DateTime, nullable=True)
    version = Column(Integer, default=0)

    # Exactly one of these is set: owner_user_id => personal, team_id => team session.
    # The exactly-one rule is enforced at app level in Wave 2 (no DB CHECK here).
    owner_user_id = Column(Integer, ForeignKey("users.id"), nullable=True, index=True)
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=True, index=True)

    __table_args__ = (
        CheckConstraint(
            "(owner_user_id IS NULL) != (team_id IS NULL)",
            name="ck_session_one_owner",
        ),
    )

    items = relationship("GlobalSessionItem", back_populates="session")
    owner = relationship("User")
    team = relationship("Team", back_populates="sessions")

class GlobalSessionItem(Base):
    __tablename__ = "global_session_items"
    
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, ForeignKey("global_sessions.id"), nullable=False)
    product_id = Column(Integer, ForeignKey("products.id"), nullable=False)
    quantity = Column(Integer, default = 1, nullable=False)
    created_at = Column(DateTime, default=func.now())
    updated_at = Column(DateTime, default=func.now(), onupdate=func.now())
    version = Column(Integer, default=0)
    # Wave 0 (additive, nullable): server-populated client-stable UUID (set in Wave 2).
    item_uuid = Column(String(36), nullable=True)

    session = relationship("GlobalSession", back_populates="items")
    product = relationship("Product")


class AppliedOp(Base):
    """Idempotency ledger: a repeat idempotency key returns the stored result
    instead of re-applying the operation (fixes FM3)."""
    __tablename__ = "applied_ops"

    # Composite PK (key, session_id): an idempotency key is unique PER SESSION,
    # not globally. Prevents a key reused across sessions from returning another
    # session's stored result (cross-tenant disclosure).
    key = Column(String(64), primary_key=True)  # the idempotency key
    session_id = Column(
        Integer, ForeignKey("global_sessions.id"), primary_key=True, nullable=False
    )
    item_id = Column(Integer, nullable=True)
    result_json = Column(Text, nullable=True)  # JSON string of the produced response
    created_at = Column(DateTime, default=func.now())


class SessionOp(Base):
    """Append-only op-log with a per-session monotonic seq (assigned by app code).
    Deletes are recorded as RemoveItem ops (tombstones)."""
    __tablename__ = "session_ops"

    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, ForeignKey("global_sessions.id"), nullable=False, index=True)
    seq = Column(Integer, nullable=False)  # per-session monotonic, assigned by app code
    op_type = Column(String(20), nullable=False)  # AddItem | ChangeQty | RemoveItem | ClearSession
    item_uuid = Column(String(36), nullable=True)
    product_id = Column(Integer, nullable=True)
    qty_delta = Column(Integer, nullable=True)
    # No longer globally unique: record_op writes None today, and a future
    # per-session use must not be blocked by a global unique constraint.
    idempotency_key = Column(String(64), nullable=True)
    actor_user_id = Column(Integer, nullable=True)
    created_at = Column(DateTime, default=func.now())

    __table_args__ = (
        Index("ix_session_ops_session_id_seq", "session_id", "seq"),
    )